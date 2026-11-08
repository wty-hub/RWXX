#!/usr/bin/env python3
"""Deterministic scheduler checks and short loopback-only proxy integration."""

import asyncio
from contextlib import redirect_stdout
from dataclasses import replace
import io
import json
import math
import os
from pathlib import Path
import signal
import sys
import time
import unittest
from unittest import mock

from authoritative_network_impairment import (
    BoundedByteQueue, DelaySchedule, ImpairmentProxy, ProxyConfig, RatePacer, _run,
)


class SchedulerTests(unittest.TestCase):
    def test_pacer_charges_each_quantum_and_cannot_bank_idle_credit(self):
        pacer = RatePacer(1000)
        self.assertEqual(pacer.quantum, 10)
        self.assertAlmostEqual(pacer.deadline(0, 10, 0), 0.01)
        self.assertAlmostEqual(pacer.deadline(0.01, 10, 0), 0.02)
        self.assertAlmostEqual(pacer.deadline(10, 10, 0), 10.01)
        self.assertAlmostEqual(pacer.deadline(10.01, 5, 12), 12.005)

    def test_scheduler_lag_does_not_produce_catch_up_burst(self):
        pacer = RatePacer(800)
        first = pacer.deadline(0, 8, 0)
        next_after_delayed_write = pacer.deadline(first + 2, 8, 0)
        self.assertAlmostEqual(next_after_delayed_write, 2.02)
        other_connection = RatePacer(800)
        self.assertAlmostEqual(other_connection.deadline(0, 8, 0), 0.01)

    def test_paced_windows_obey_rate_plus_one_quantum_bound(self):
        pacer = RatePacer(1234)
        writes = []
        now = 0.0
        for index in range(100):
            size = 1 + index % pacer.quantum
            now = pacer.deadline(now, size, 0)
            writes.append((now, size))
            if index % 13 == 0:
                now += 0.035  # deterministic event-loop scheduling delay
        for left in range(len(writes)):
            for right in range(left, len(writes)):
                duration = writes[right][0] - writes[left][0]
                count = sum(size for _, size in writes[left:right + 1])
                self.assertLessEqual(count, 1234 * duration + pacer.quantum + 1e-9)
        with self.assertRaises(ValueError):
            pacer.deadline(now, pacer.quantum + 1, 0)

    def test_low_rate_still_paces_one_byte_and_unlimited_still_delays(self):
        low = RatePacer(1)
        self.assertEqual(low.quantum, 1)
        self.assertEqual(low.deadline(2, 1, 0), 3)
        unlimited = RatePacer(0)
        self.assertEqual(unlimited.deadline(2, 4096, 3), 3)

    def test_jitter_reproducible_nonnegative_and_order_preserving(self):
        one = DelaySchedule(20, 80, 17)
        two = DelaySchedule(20, 80, 17)
        other = DelaySchedule(20, 80, 18)
        arrivals = [i / 1000 for i in range(100)]
        a = [one.ready_at(t) for t in arrivals]
        b = [two.ready_at(t) for t in arrivals]
        c = [other.ready_at(t) for t in arrivals]
        self.assertEqual(a, b)
        self.assertNotEqual(a, c)
        self.assertEqual(a, sorted(a))
        for arrival, due in zip(arrivals, a):
            self.assertGreaterEqual(due, arrival)
            self.assertLessEqual(due, arrival + 0.100000001)

    def test_configuration_rejects_nonlocal_listener_invalid_bounds_and_dns(self):
        base = ProxyConfig("127.0.0.1", 12345)
        base.validate()
        replace(base, target_host="::1", listen_host="::1").validate()
        for change in [dict(listen_host="0.0.0.0"), dict(listen_host="192.0.2.1"),
                       dict(target_host="hostname.invalid"), dict(target_host="0.0.0.0"),
                       dict(target_host="fe80::1%en0"),
                       dict(target_host="224.0.0.1"), dict(target_port=0),
                       dict(max_connections=0), dict(max_connections=257),
                       dict(max_buffer_bytes=0), dict(max_buffer_bytes=64 * 1024 * 1024 + 1),
                       dict(upstream_bytes_per_second=-1), dict(jitter_ms=-1),
                       dict(one_way_delay_ms=math.inf), dict(jitter_ms=math.nan)]:
            with self.subTest(change=change), self.assertRaises(ValueError):
                replace(base, **change).validate()


class QueueTests(unittest.IsolatedAsyncioTestCase):
    async def test_capacity_includes_reserved_queued_and_active_bytes(self):
        queue = BoundedByteQueue(8)
        self.assertEqual(await queue.reserve(4096), 8)
        waiter = asyncio.create_task(queue.reserve(4))
        await asyncio.sleep(0)
        self.assertFalse(waiter.done())
        await queue.commit(8, b"abcdefgh", 0)
        chunk = await queue.get()
        self.assertEqual(chunk.data, b"abcdefgh")
        await asyncio.sleep(0)
        self.assertFalse(waiter.done(), "Popping an in-flight chunk must not free its capacity")
        await queue.release(8)
        self.assertEqual(await asyncio.wait_for(waiter, 0.2), 4)
        await queue.commit(4, b"", 0)
        self.assertIsNone(await queue.get())
        self.assertEqual(queue.buffered, 0)
        self.assertEqual(queue.peak_buffered, 8)

    async def test_short_read_releases_unused_reservation_and_eof_drains_queue(self):
        queue = BoundedByteQueue(8)
        await queue.reserve(8)
        await queue.commit(8, b"abc", 0)
        self.assertEqual(queue.buffered, 3)
        reserved = await queue.reserve(8)
        self.assertEqual(reserved, 5)
        await queue.commit(reserved, b"", 0)
        self.assertEqual((await queue.get()).data, b"abc")
        await queue.release(3)
        self.assertIsNone(await queue.get())


class LoopbackTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.echo_tasks = set()
        self.echo_writers = set()
        self.echo_connections = 0
        self.proxies = []

        async def echo(reader, writer):
            task = asyncio.current_task()
            self.echo_tasks.add(task)
            self.echo_writers.add(writer)
            self.echo_connections += 1
            try:
                while True:
                    data = await reader.read(4096)
                    if not data:
                        break
                    writer.write(data)
                    await writer.drain()
            except (OSError, asyncio.CancelledError):
                pass
            finally:
                writer.close()
                try:
                    await writer.wait_closed()
                except OSError:
                    pass
                self.echo_writers.discard(writer)
                self.echo_tasks.discard(task)

        self.server = await asyncio.start_server(echo, "127.0.0.1", 0)
        self.target_port = self.server.sockets[0].getsockname()[1]

    async def asyncTearDown(self):
        for proxy in self.proxies:
            await proxy.close()
        self.server.close()
        await self.server.wait_closed()
        for writer in tuple(self.echo_writers):
            writer.close()
        tasks = tuple(self.echo_tasks)
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)

    async def proxy(self, **options):
        events = []
        proxy = ImpairmentProxy(ProxyConfig("127.0.0.1", self.target_port, **options), events.append)
        await proxy.start()
        self.proxies.append(proxy)
        return proxy, events

    async def wait_until(self, predicate, timeout=1.0):
        deadline = asyncio.get_running_loop().time() + timeout
        while not predicate():
            if asyncio.get_running_loop().time() > deadline:
                self.fail("Timed out waiting for local proxy state")
            await asyncio.sleep(0.005)

    async def test_binary_transparency_half_close_and_statistics(self):
        proxy, events = await self.proxy()
        reader, writer = await asyncio.open_connection(*proxy.address)
        payload = bytes(range(256)) * 4 + b"\x00\xffcredential-like-data-not-for-logs"
        writer.write(payload)
        await writer.drain()
        writer.write_eof()
        actual = await asyncio.wait_for(reader.read(), 1)
        self.assertEqual(actual, payload)
        writer.close()
        await writer.wait_closed()
        await self.wait_until(lambda: proxy.completed == 1)
        await proxy.close()
        summary = proxy.summary()
        for direction in ("upstream", "downstream"):
            self.assertEqual(summary[direction]["bytesRead"], len(payload))
            self.assertEqual(summary[direction]["bytesForwarded"], len(payload))
        self.assertTrue(summary["cleanupComplete"])
        self.assertEqual(summary["endReasons"], {"closed": 1})
        self.assertNotIn("credential-like", json.dumps(events + [summary]))
        self.assertEqual(events[0]["event"], "ready")
        self.assertNotEqual(events[0]["listenPort"], 0)

    async def test_actual_delay_rate_and_bounded_payload_buffer(self):
        proxy, _ = await self.proxy(upstream_bytes_per_second=4096,
                                   downstream_bytes_per_second=2048,
                                   one_way_delay_ms=25, max_buffer_bytes=4096)
        reader, writer = await asyncio.open_connection(*proxy.address)
        payload = bytes(range(256)) * 4
        started = time.monotonic()
        writer.write(payload)
        await writer.drain()
        first = await asyncio.wait_for(reader.readexactly(1), 2)
        first_elapsed = time.monotonic() - started
        rest = await asyncio.wait_for(reader.readexactly(len(payload) - 1), 2)
        elapsed = time.monotonic() - started
        self.assertEqual(first + rest, payload)
        self.assertGreaterEqual(first_elapsed, 0.04, "Both directions must incur the configured delay")
        self.assertGreaterEqual(elapsed, (len(payload) - 20) / 2048,
                                "A delayed whole-buffer burst must not pass the rate check")
        self.assertLess(elapsed, 3)
        writer.write_eof()
        self.assertEqual(await asyncio.wait_for(reader.read(), 1), b"")
        writer.close()
        await writer.wait_closed()
        await self.wait_until(lambda: proxy.completed == 1)
        record = proxy.summary()["recentConnections"][0]
        for direction in ("upstream", "downstream"):
            self.assertLessEqual(record[direction]["peakBufferedBytes"], 4096)
            self.assertGreater(record[direction]["writes"], 10)

    async def test_connection_limit_rejects_extra_without_disrupting_first(self):
        proxy, _ = await self.proxy(max_connections=1)
        first_reader, first_writer = await asyncio.open_connection(*proxy.address)
        await self.wait_until(lambda: self.echo_connections == 1)
        rejected_reader, rejected_writer = await asyncio.open_connection(*proxy.address)
        self.assertEqual(await asyncio.wait_for(rejected_reader.read(1), 0.5), b"")
        rejected_writer.close()
        await rejected_writer.wait_closed()
        first_writer.write(b"still alive")
        await first_writer.drain()
        self.assertEqual(await asyncio.wait_for(first_reader.readexactly(11), 0.5), b"still alive")
        first_writer.write_eof()
        await first_reader.read()
        first_writer.close()
        await first_writer.wait_closed()
        await self.wait_until(lambda: proxy.completed == 1)
        third_reader, third_writer = await asyncio.open_connection(*proxy.address)
        third_writer.write(b"new")
        await third_writer.drain()
        self.assertEqual(await asyncio.wait_for(third_reader.readexactly(3), 0.5), b"new")
        third_writer.close()
        await third_writer.wait_closed()
        await self.wait_until(lambda: proxy.completed == 2)
        summary = proxy.summary()
        self.assertEqual(summary["rejectedConnections"], 1)
        self.assertEqual(summary["acceptedConnections"], 2)
        self.assertEqual(summary["peakActiveConnections"], 1)

    async def test_two_connections_have_independent_data_and_buffer_budgets(self):
        proxy, _ = await self.proxy(max_connections=2, max_buffer_bytes=64,
                                   one_way_delay_ms=3, jitter_ms=2, seed=43)

        async def exchange(payload):
            reader, writer = await asyncio.open_connection(*proxy.address)
            writer.write(payload)
            await writer.drain()
            writer.write_eof()
            received = await asyncio.wait_for(reader.read(), 1)
            writer.close()
            await writer.wait_closed()
            return received

        a, b = bytes(range(255)) * 2, b"independent connection" * 17
        self.assertEqual(await asyncio.gather(exchange(a), exchange(b)), [a, b])
        await self.wait_until(lambda: proxy.completed == 2)
        summary = proxy.summary()
        self.assertEqual(summary["peakActiveConnections"], 2)
        self.assertEqual(summary["upstream"]["bytesForwarded"], len(a) + len(b))
        for connection in summary["recentConnections"]:
            for direction in ("upstream", "downstream"):
                self.assertLessEqual(connection[direction]["peakBufferedBytes"], 64)

    async def test_close_cancels_long_delay_full_buffer_and_releases_listener(self):
        proxy, _ = await self.proxy(upstream_bytes_per_second=1,
                                   one_way_delay_ms=60000, max_buffer_bytes=64)
        address = proxy.address
        reader, writer = await asyncio.open_connection(*address)
        writer.write(b"x" * 4096)
        await writer.drain()
        await self.wait_until(lambda: any(s.upstream.bytes_read == 64 for s in proxy.active_stats.values()))
        started = time.monotonic()
        await proxy.close()
        self.assertLess(time.monotonic() - started, 0.75)
        self.assertTrue(proxy.summary()["cleanupComplete"])
        self.assertFalse(proxy.connection_tasks)
        self.assertEqual(proxy.summary()["upstream"]["bytesForwarded"], 0)
        with self.assertRaises(OSError):
            await asyncio.open_connection(*address)
        try:
            await asyncio.wait_for(reader.read(), 0.5)
        except OSError:
            pass
        writer.close()
        try:
            await writer.wait_closed()
        except OSError:
            pass

    async def test_cancel_before_handler_starts_releases_accepted_socket(self):
        proxy, _ = await self.proxy()
        original = asyncio.create_task

        def cancel_before_start(coroutine, *, name=None, **kwargs):
            task = original(coroutine, name=name, **kwargs)
            if name and name.startswith("impairment-connection-"):
                task.cancel()
            return task

        with mock.patch("authoritative_network_impairment.asyncio.create_task", side_effect=cancel_before_start):
            reader, writer = await asyncio.open_connection(*proxy.address)
            try:
                self.assertEqual(await asyncio.wait_for(reader.read(1), 0.5), b"")
                self.assertEqual(proxy.accepted, 1)
                self.assertEqual(proxy.completed, 0, "The handler must not have executed")
            finally:
                writer.close()
                await writer.wait_closed()
        await proxy.close()
        self.assertTrue(proxy.summary()["cleanupComplete"])

    async def test_accept_failure_stops_cli_with_nonzero_sanitized_result(self):
        loop = asyncio.get_running_loop()
        captured = io.StringIO()
        failed_accept = mock.AsyncMock(side_effect=OSError("private exception text"))
        with mock.patch.object(loop, "sock_accept", failed_accept), redirect_stdout(captured):
            result = await asyncio.wait_for(_run(ProxyConfig("127.0.0.1", self.target_port), 30), 1)
        self.assertEqual(result, 1)
        records = [json.loads(line) for line in captured.getvalue().splitlines()]
        self.assertEqual(records[-1]["stopReason"], "accept_error")
        self.assertTrue(records[-1]["acceptError"])
        self.assertTrue(records[-1]["cleanupComplete"])
        self.assertNotIn("private exception text", captured.getvalue())

    async def test_unexpected_handler_failure_is_fatal_and_closes_socket(self):
        proxy, _ = await self.proxy()
        failure = mock.AsyncMock(side_effect=RuntimeError("private handler text"))
        with mock.patch.object(proxy, "_handle_connection", failure):
            reader, writer = await asyncio.open_connection(*proxy.address)
            try:
                await asyncio.wait_for(proxy.failed.wait(), 0.5)
                self.assertEqual(await asyncio.wait_for(reader.read(1), 0.5), b"")
            finally:
                writer.close()
                await writer.wait_closed()
        await proxy.close()
        summary = proxy.summary()
        self.assertTrue(summary["taskError"])
        self.assertTrue(summary["cleanupComplete"])
        self.assertNotIn("private handler text", json.dumps(summary))


@unittest.skipUnless(os.name == "posix", "POSIX signals are required")
class CliLifecycleTests(unittest.IsolatedAsyncioTestCase):
    async def launch(self, *extra):
        script = Path(__file__).with_name("authoritative_network_impairment.py")
        return await asyncio.create_subprocess_exec(
            sys.executable, "-B", str(script), "--target-host", "127.0.0.1", "--target-port", "9",
            *extra, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)

    async def test_sigint_and_sigterm_emit_bounded_clean_summary(self):
        for signum in (signal.SIGINT, signal.SIGTERM):
            process = await self.launch("--duration", "30")
            try:
                ready = json.loads(await asyncio.wait_for(process.stdout.readline(), 2))
                self.assertEqual(ready["event"], "ready")
                process.send_signal(signum)
                output, error = await asyncio.wait_for(process.communicate(), 2)
                self.assertEqual(process.returncode, 0, error.decode())
                summary = json.loads(output)
                self.assertEqual(summary["event"], "summary")
                self.assertEqual(summary["stopReason"], "signal")
                self.assertTrue(summary["cleanupComplete"])
            finally:
                if process.returncode is None:
                    process.kill()
                    await process.wait()

    async def test_duration_terminates_without_signals(self):
        process = await self.launch("--duration", "0.05")
        try:
            output, error = await asyncio.wait_for(process.communicate(), 2)
            self.assertEqual(process.returncode, 0, error.decode())
            records = [json.loads(line) for line in output.splitlines()]
            self.assertEqual([record["event"] for record in records], ["ready", "summary"])
            self.assertEqual(records[-1]["stopReason"], "duration")
            self.assertTrue(records[-1]["cleanupComplete"])
        finally:
            if process.returncode is None:
                process.kill()
                await process.wait()


if __name__ == "__main__":
    unittest.main()
