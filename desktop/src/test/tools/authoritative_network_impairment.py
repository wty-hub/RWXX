#!/usr/bin/env python3
"""A bounded, process-local TCP latency and bandwidth test proxy.

Only the listener is restricted to loopback; --target-host must be an explicit
IPv4/IPv6 address. No system network settings are changed. This byte-stream
proxy does not simulate packet loss, TCP retransmission, or packet-level jitter.
"""

from __future__ import annotations

import argparse
import asyncio
from collections import deque
from dataclasses import dataclass, field
import ipaddress
import json
import math
import random
import signal
import socket
import time
from typing import Callable

READ_CHUNK_BYTES = 4096
MAX_CONNECTIONS = 256
MAX_BUFFER_BYTES = 64 * 1024 * 1024
RECENT_CONNECTIONS = 16


@dataclass(frozen=True)
class ProxyConfig:
    target_host: str
    target_port: int
    listen_host: str = "127.0.0.1"
    listen_port: int = 0
    upstream_bytes_per_second: int = 0
    downstream_bytes_per_second: int = 0
    one_way_delay_ms: float = 0
    jitter_ms: float = 0
    seed: int = 0
    max_buffer_bytes: int = 65536
    max_connections: int = 8
    connect_timeout_seconds: float = 5

    def validate(self) -> None:
        try:
            target = ipaddress.ip_address(self.target_host)
            listener = ipaddress.ip_address(self.listen_host)
        except ValueError as exc:
            raise ValueError("Target and listen hosts must be explicit IP addresses") from exc
        if "%" in self.target_host or "%" in self.listen_host:
            raise ValueError("Scoped IPv6 addresses are not supported")
        if not listener.is_loopback:
            raise ValueError("The listener must use a loopback address")
        if target.is_unspecified or target.is_multicast:
            raise ValueError("The target must be a unicast IP address")
        for value, lower, upper, label in [
            (self.target_port, 1, 65535, "target port"),
            (self.listen_port, 0, 65535, "listen port"),
            (self.upstream_bytes_per_second, 0, (1 << 53) - 1, "upstream rate"),
            (self.downstream_bytes_per_second, 0, (1 << 53) - 1, "downstream rate"),
            (self.max_buffer_bytes, 1, MAX_BUFFER_BYTES, "buffer capacity"),
            (self.max_connections, 1, MAX_CONNECTIONS, "connection limit"),
        ]:
            if type(value) is not int or not lower <= value <= upper:
                raise ValueError(f"Invalid {label}")
        if type(self.seed) is not int:
            raise ValueError("Seed must be an integer")
        for value, label in [(self.one_way_delay_ms, "delay"), (self.jitter_ms, "jitter")]:
            if not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0:
                raise ValueError(f"Invalid {label}")
        if not math.isfinite(self.connect_timeout_seconds) or self.connect_timeout_seconds <= 0:
            raise ValueError("Invalid connect timeout")


class DelaySchedule:
    """Independent per-direction seeded jitter; byte order is preserved."""

    def __init__(self, delay_ms: float, jitter_ms: float, seed: int):
        self.delay = delay_ms / 1000
        self.jitter = jitter_ms / 1000
        self.random = random.Random(seed)
        self.last_ready = 0.0

    def ready_at(self, arrival: float) -> float:
        offset = self.random.uniform(-self.jitter, self.jitter) if self.jitter else 0.0
        desired = arrival + max(0.0, self.delay + offset)
        self.last_ready = max(self.last_ready, desired)
        return self.last_ready


class RatePacer:
    """No idle credit or catch-up bursts; at most one small write quantum.

    For a positive rate R, writes are scheduled with at least n/R seconds for
    each n-byte quantum. A time window may contain at most R*t + quantum bytes
    at this proxy's socket-write boundary. OS/TCP delivery may regroup writes.
    """

    def __init__(self, rate: int):
        self.rate = rate
        self.quantum = READ_CHUNK_BYTES if rate == 0 else min(READ_CHUNK_BYTES, max(1, rate // 100))
        self.previous_deadline = 0.0

    def deadline(self, now: float, size: int, ready_at: float) -> float:
        if not 1 <= size <= self.quantum:
            raise ValueError("Write exceeds pacing quantum")
        start = max(now, ready_at, self.previous_deadline)
        self.previous_deadline = start + (size / self.rate if self.rate else 0.0)
        return self.previous_deadline


@dataclass(frozen=True)
class _Chunk:
    data: bytes
    ready_at: float


class BoundedByteQueue:
    """Capacity includes queued bytes, the active write, and read reservations.

    Reserving before recv prevents an extra unaccounted read buffer. Removing a
    chunk does not release its capacity: the sender releases bytes only after a
    completed socket write. Each direction has its own queue.
    """

    def __init__(self, capacity: int):
        self.capacity = capacity
        self.buffered = 0
        self.peak_buffered = 0
        self.items: deque[_Chunk] = deque()
        self.eof = False
        self.changed = asyncio.Condition()

    async def reserve(self, maximum: int) -> int:
        async with self.changed:
            await self.changed.wait_for(lambda: self.buffered < self.capacity)
            amount = min(maximum, self.capacity - self.buffered)
            self.buffered += amount
            self.peak_buffered = max(self.peak_buffered, self.buffered)
            return amount

    async def commit(self, reserved: int, data: bytes, ready_at: float) -> None:
        if not 0 <= len(data) <= reserved:
            raise ValueError("Read reservation mismatch")
        async with self.changed:
            self.buffered -= reserved - len(data)
            if data:
                self.items.append(_Chunk(data, ready_at))
            else:
                self.eof = True
            self.changed.notify_all()

    async def get(self) -> _Chunk | None:
        async with self.changed:
            await self.changed.wait_for(lambda: bool(self.items) or self.eof)
            return self.items.popleft() if self.items else None

    async def release(self, amount: int) -> None:
        async with self.changed:
            if not 0 <= amount <= self.buffered:
                raise ValueError("Invalid buffer release")
            self.buffered -= amount
            self.changed.notify_all()


@dataclass
class DirectionStats:
    bytes_read: int = 0
    bytes_forwarded: int = 0
    writes: int = 0
    peak_buffered_bytes: int = 0

    def json(self) -> dict:
        return {
            "bytesRead": self.bytes_read,
            "bytesForwarded": self.bytes_forwarded,
            "writes": self.writes,
            "peakBufferedBytes": self.peak_buffered_bytes,
        }


@dataclass
class ConnectionStats:
    connection_id: int
    started: float
    upstream: DirectionStats = field(default_factory=DirectionStats)
    downstream: DirectionStats = field(default_factory=DirectionStats)
    end_reason: str = "closed"
    elapsed_seconds: float = 0

    def json(self) -> dict:
        return {
            "connectionId": self.connection_id,
            "elapsedSeconds": round(self.elapsed_seconds, 6),
            "endReason": self.end_reason,
            "upstream": self.upstream.json(),
            "downstream": self.downstream.json(),
        }


class ImpairmentProxy:
    def __init__(self, config: ProxyConfig, emit: Callable[[dict], None] | None = None):
        config.validate()
        self.config = config
        self.emit = emit or (lambda record: None)
        self.listener: socket.socket | None = None
        self.accept_task: asyncio.Task | None = None
        self.accept_error = False
        self.task_error = False
        self.failed = asyncio.Event()
        self.connection_tasks: set[asyncio.Task] = set()
        self.active_stats: dict[int, ConnectionStats] = {}
        self.recent: deque[dict] = deque(maxlen=RECENT_CONNECTIONS)
        self.accepted = 0
        self.rejected = 0
        self.completed = 0
        self.peak_active = 0
        self.totals = {"upstream": DirectionStats(), "downstream": DirectionStats()}
        self.end_reasons: dict[str, int] = {}
        self.started = 0.0
        self.ended: float | None = None
        self.closing = False
        self.close_lock = asyncio.Lock()

    @property
    def address(self) -> tuple[str, int]:
        if self.listener is None:
            raise RuntimeError("Proxy has not started")
        address = self.listener.getsockname()
        return address[0], address[1]

    async def start(self) -> tuple[str, int]:
        if self.listener is not None or self.closing:
            raise RuntimeError("Proxy cannot be restarted")
        family = socket.AF_INET6 if ipaddress.ip_address(self.config.listen_host).version == 6 else socket.AF_INET
        listener = socket.socket(family, socket.SOCK_STREAM)
        try:
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.setblocking(False)
            listener.bind((self.config.listen_host, self.config.listen_port))
            listener.listen(self.config.max_connections)
        except BaseException:
            listener.close()
            raise
        self.listener = listener
        self.started = time.monotonic()
        self.accept_task = asyncio.create_task(self._accept_loop(), name="impairment-accept")
        self.accept_task.add_done_callback(self._accept_finished)
        host, port = self.address
        self.emit({
            "event": "ready", "listenHost": host, "listenPort": port,
            "targetHost": self.config.target_host, "targetPort": self.config.target_port,
            "upstreamBytesPerSecond": self.config.upstream_bytes_per_second,
            "downstreamBytesPerSecond": self.config.downstream_bytes_per_second,
            "oneWayDelayMs": self.config.one_way_delay_ms, "jitterMs": self.config.jitter_ms,
            "seed": self.config.seed, "maxBufferBytesPerDirection": self.config.max_buffer_bytes,
            "maxConnections": self.config.max_connections,
            "upstreamWriteQuantum": RatePacer(self.config.upstream_bytes_per_second).quantum,
            "downstreamWriteQuantum": RatePacer(self.config.downstream_bytes_per_second).quantum,
            "packetLossSimulation": False,
        })
        return host, port

    def _accept_finished(self, task: asyncio.Task) -> None:
        if task.cancelled():
            return
        if task.exception() is not None:
            self.accept_error = True
            self.failed.set()

    def _connection_finished(self, task: asyncio.Task, client: socket.socket) -> None:
        # A task cancelled before its first execution never enters the handler's
        # try/finally. Keep a socket owner outside that coroutine as well.
        client.close()
        self.connection_tasks.discard(task)
        if not task.cancelled() and task.exception() is not None:
            self.task_error = True
            self.failed.set()

    async def _accept_loop(self) -> None:
        loop = asyncio.get_running_loop()
        while not self.closing:
            client, _ = await loop.sock_accept(self.listener)
            if self.closing or len(self.connection_tasks) >= self.config.max_connections:
                self.rejected += 1
                client.close()
                continue
            try:
                client.setblocking(False)
                client.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            except BaseException:
                client.close()
                raise
            self.accepted += 1
            connection_id = self.accepted
            task = asyncio.create_task(self._handle_connection(client, connection_id),
                                       name=f"impairment-connection-{connection_id}")
            self.connection_tasks.add(task)
            task.add_done_callback(lambda finished, owned=client: self._connection_finished(finished, owned))
            self.peak_active = max(self.peak_active, len(self.connection_tasks))

    async def _connect_target(self) -> socket.socket:
        family = socket.AF_INET6 if ipaddress.ip_address(self.config.target_host).version == 6 else socket.AF_INET
        target = socket.socket(family, socket.SOCK_STREAM)
        target.setblocking(False)
        target.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        try:
            await asyncio.wait_for(asyncio.get_running_loop().sock_connect(
                target, (self.config.target_host, self.config.target_port)),
                timeout=self.config.connect_timeout_seconds)
            return target
        except BaseException:
            target.close()
            raise

    async def _forward(self, source: socket.socket, destination: socket.socket, rate: int,
                       connection_id: int, direction: int, stats: DirectionStats) -> None:
        loop = asyncio.get_running_loop()
        queue = BoundedByteQueue(self.config.max_buffer_bytes)
        # Stable separate streams; no payload-dependent seed and no global RNG.
        seed = self.config.seed * 0x100000001B3 + connection_id * 0x9E3779B97F4A7C15 + direction
        delays = DelaySchedule(self.config.one_way_delay_ms, self.config.jitter_ms, seed)
        pacer = RatePacer(rate)

        async def receive() -> None:
            while True:
                reserved = await queue.reserve(READ_CHUNK_BYTES)
                stats.peak_buffered_bytes = max(stats.peak_buffered_bytes, queue.peak_buffered)
                try:
                    data = await loop.sock_recv(source, reserved)
                except BaseException:
                    await queue.release(reserved)
                    raise
                stats.bytes_read += len(data)
                await queue.commit(reserved, data, delays.ready_at(loop.time()))
                reached_eof = not data
                del data
                if reached_eof:
                    return

        async def send() -> None:
            while True:
                chunk = await queue.get()
                if chunk is None:
                    try:
                        destination.shutdown(socket.SHUT_WR)
                    except OSError:
                        pass
                    return
                view = memoryview(chunk.data)
                offset = 0
                while offset < len(view):
                    count = min(pacer.quantum, len(view) - offset)
                    due = pacer.deadline(loop.time(), count, chunk.ready_at)
                    wait = due - loop.time()
                    if wait > 0:
                        await asyncio.sleep(wait)
                    await loop.sock_sendall(destination, view[offset:offset + count])
                    stats.bytes_forwarded += count
                    stats.writes += 1
                    offset += count
                # Retain the reservation until the entire backing bytes object
                # can be released; sent prefixes still occupy that allocation.
                released = len(view)
                del view, chunk
                await queue.release(released)

        receive_task = asyncio.create_task(receive(), name="impairment-read")
        send_task = asyncio.create_task(send(), name="impairment-paced-write")
        try:
            await asyncio.gather(receive_task, send_task)
        finally:
            for task in (receive_task, send_task):
                if not task.done():
                    task.cancel()
            await asyncio.gather(receive_task, send_task, return_exceptions=True)

    async def _handle_connection(self, client: socket.socket, connection_id: int) -> None:
        target = None
        transfers: list[asyncio.Task] = []
        stats = ConnectionStats(connection_id, time.monotonic())
        self.active_stats[connection_id] = stats
        try:
            target = await self._connect_target()
            transfers = [
                asyncio.create_task(self._forward(client, target, self.config.upstream_bytes_per_second,
                                                 connection_id, 0, stats.upstream)),
                asyncio.create_task(self._forward(target, client, self.config.downstream_bytes_per_second,
                                                 connection_id, 1, stats.downstream)),
            ]
            await asyncio.gather(*transfers)
        except asyncio.CancelledError:
            stats.end_reason = "proxy_stopped"
            raise
        except asyncio.TimeoutError:
            stats.end_reason = "connect_timeout"
        except OSError:
            stats.end_reason = "socket_error"
        except Exception:
            # Never emit exception strings: they may contain destination or data.
            stats.end_reason = "internal_error"
        finally:
            for task in transfers:
                if not task.done():
                    task.cancel()
            # Close owned sockets before awaiting task cleanup, so cancellation
            # cannot strand an open endpoint inside a nested finally block.
            client.close()
            if target is not None:
                target.close()
            if transfers:
                await asyncio.gather(*transfers, return_exceptions=True)
            stats.elapsed_seconds = time.monotonic() - stats.started
            self.active_stats.pop(connection_id, None)
            for direction in ("upstream", "downstream"):
                source = getattr(stats, direction)
                total = self.totals[direction]
                total.bytes_read += source.bytes_read
                total.bytes_forwarded += source.bytes_forwarded
                total.writes += source.writes
                total.peak_buffered_bytes = max(total.peak_buffered_bytes, source.peak_buffered_bytes)
            self.completed += 1
            self.end_reasons[stats.end_reason] = self.end_reasons.get(stats.end_reason, 0) + 1
            self.recent.append(stats.json())
            if stats.end_reason == "internal_error":
                self.task_error = True
                self.failed.set()

    async def close(self) -> None:
        async with self.close_lock:
            if self.ended is not None:
                return
            self.closing = True
            if self.listener is not None:
                self.listener.close()
            if self.accept_task is not None:
                self.accept_task.cancel()
                await asyncio.gather(self.accept_task, return_exceptions=True)
            tasks = tuple(self.connection_tasks)
            for task in tasks:
                task.cancel()
            if tasks:
                await asyncio.wait_for(asyncio.gather(*tasks, return_exceptions=True), timeout=2.0)
            self.connection_tasks.clear()
            self.ended = time.monotonic()

    def summary(self, stop_reason: str = "closed") -> dict:
        end = self.ended if self.ended is not None else time.monotonic()
        return {
            "event": "summary", "stopReason": stop_reason,
            "elapsedSeconds": round(max(0.0, end - self.started), 6) if self.started else 0.0,
            "acceptedConnections": self.accepted, "rejectedConnections": self.rejected,
            "completedConnections": self.completed, "activeConnections": len(self.active_stats),
            "acceptError": self.accept_error,
            "taskError": self.task_error,
            "peakActiveConnections": self.peak_active,
            "upstream": self.totals["upstream"].json(),
            "downstream": self.totals["downstream"].json(),
            "endReasons": dict(self.end_reasons), "recentConnections": list(self.recent),
            "omittedConnectionDetails": max(0, self.completed - len(self.recent)),
            "cleanupComplete": self.ended is not None and not self.connection_tasks and not self.active_stats,
            "countingScope": "Completed TCP-stream write bytes; includes TLS records if used, excludes IP/TCP headers",
            "packetLossSimulation": False,
        }


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="Rates are per connection and direction; 0 means unlimited. Delay/jitter apply to read chunks, "
               "not IP packets. Jitter is uniform in [-jitter,+jitter], clamped at zero delay and ordered. "
               "The byte cap includes queued/in-flight/reserved proxy payload; OS socket buffers and Python "
               "object overhead are separate. Never use byte dropping to model TCP packet loss.")
    parser.add_argument("--target-host", required=True, help="Explicit IPv4/IPv6 address; no DNS lookup")
    parser.add_argument("--target-port", required=True, type=int)
    parser.add_argument("--listen-host", default="127.0.0.1")
    parser.add_argument("--listen-port", type=int, default=0)
    parser.add_argument("--upstream-bytes-per-second", type=int, default=0)
    parser.add_argument("--downstream-bytes-per-second", type=int, default=0)
    parser.add_argument("--one-way-delay-ms", type=float, default=0)
    parser.add_argument("--jitter-ms", type=float, default=0)
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument("--max-buffer-bytes", type=int, default=65536)
    parser.add_argument("--max-connections", type=int, default=8)
    parser.add_argument("--duration", type=float, default=30, help="Seconds; 0 runs until SIGINT/SIGTERM")
    parser.add_argument("--connect-timeout-seconds", type=float, default=5)
    return parser


async def _run(config: ProxyConfig, duration: float) -> int:
    def emit(record: dict) -> None:
        print(json.dumps(record, separators=(",", ":"), allow_nan=False), flush=True)

    proxy = ImpairmentProxy(config, emit)
    stopped = asyncio.Event()
    loop = asyncio.get_running_loop()
    installed_signals = []
    for signum in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(signum, stopped.set)
            installed_signals.append(signum)
        except (NotImplementedError, RuntimeError):
            pass
    reason = "signal"
    failure_watch = None
    try:
        await proxy.start()

        async def watch_accept_failure() -> None:
            await proxy.failed.wait()
            stopped.set()

        failure_watch = asyncio.create_task(watch_accept_failure(), name="impairment-failure-watch")
        if duration:
            try:
                await asyncio.wait_for(stopped.wait(), timeout=duration)
            except asyncio.TimeoutError:
                reason = "duration"
        else:
            await stopped.wait()
        if proxy.accept_error:
            reason = "accept_error"
        elif proxy.task_error:
            reason = "task_error"
    finally:
        if failure_watch is not None:
            failure_watch.cancel()
            await asyncio.gather(failure_watch, return_exceptions=True)
        await proxy.close()
        for signum in installed_signals:
            loop.remove_signal_handler(signum)
        emit(proxy.summary(reason))
    return 1 if proxy.accept_error or proxy.task_error else 0


def main() -> int:
    parser = _parser()
    args = parser.parse_args()
    if not math.isfinite(args.duration) or args.duration < 0:
        parser.error("Duration must be finite and nonnegative")
    config = ProxyConfig(
        target_host=args.target_host, target_port=args.target_port,
        listen_host=args.listen_host, listen_port=args.listen_port,
        upstream_bytes_per_second=args.upstream_bytes_per_second,
        downstream_bytes_per_second=args.downstream_bytes_per_second,
        one_way_delay_ms=args.one_way_delay_ms, jitter_ms=args.jitter_ms,
        seed=args.seed, max_buffer_bytes=args.max_buffer_bytes,
        max_connections=args.max_connections, connect_timeout_seconds=args.connect_timeout_seconds,
    )
    try:
        config.validate()
    except ValueError as exc:
        parser.error(str(exc))
    try:
        return asyncio.run(_run(config, args.duration))
    except KeyboardInterrupt:
        return 130
    except OSError:
        print(json.dumps({"event": "error", "reason": "socket_setup_failed"}), flush=True)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
