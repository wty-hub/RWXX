"""Check packaged LWJGL libraries and their binary architecture without loading native code."""
import argparse
from collections import Counter
import json
from pathlib import Path
import struct
import zipfile


PLATFORMS = {
    'linux-x64': ('linux/x64', 'elf', 62),
    'linux-arm64': ('linux/arm64', 'elf', 183),
    'windows-x64': ('windows/x64', 'pe', 0x8664),
    'macos-x64': ('macos/x64', 'macho', 0x01000007),
    'macos-arm64': ('macos/arm64', 'macho', 0x0100000C),
}
MODULE_LIBRARIES = {
    'lwjgl': ('', 'liblwjgl', 'lwjgl'),
    'lwjgl-glfw': ('glfw', 'libglfw', 'glfw'),
    'lwjgl-opengl': ('opengl', 'liblwjgl_opengl', 'lwjgl_opengl'),
    'lwjgl-openal': ('openal', 'libopenal', 'OpenAL'),
    'lwjgl-jemalloc': ('jemalloc', 'libjemalloc', 'jemalloc'),
    'lwjgl-vma': ('vma', 'liblwjgl_vma', 'lwjgl_vma'),
    'lwjgl-shaderc': ('shaderc', 'libshaderc', 'shaderc'),
    'lwjgl-nfd': ('nfd', 'liblwjgl_nfd', 'lwjgl_nfd'),
    'lwjgl-stb': ('stb', 'liblwjgl_stb', 'lwjgl_stb'),
    'lwjgl-msdfgen': ('msdfgen', 'liblwjgl_msdfgen', 'lwjgl_msdfgen'),
}


def required_entries(platform):
    prefix, binary_format, _ = PLATFORMS[platform]
    extension = {'elf': '.so', 'pe': '.dll', 'macho': '.dylib'}[binary_format]
    result = {}
    for module, (directory, unix_name, windows_name) in MODULE_LIBRARIES.items():
        library = (windows_name if binary_format == 'pe' else unix_name) + extension
        result[module] = '/'.join(filter(None, (prefix, 'org/lwjgl', directory, library)))
    # Vulkan bindings use the installed system loader on Linux/Windows; macOS bundles MoltenVK.
    if binary_format == 'macho':
        result['lwjgl-vulkan-moltenvk'] = f'{prefix}/org/lwjgl/vulkan/libMoltenVK.dylib'
    return result


def binary_architecture(stream):
    header = stream.read(64)
    if header[:4] == b'\x7fELF':
        if len(header) < 20 or header[4:6] != b'\x02\x01':
            raise ValueError('expected a 64-bit little-endian ELF library')
        return 'elf', struct.unpack_from('<H', header, 18)[0]
    if header[:4] == b'\xcf\xfa\xed\xfe':
        return 'macho', struct.unpack_from('<I', header, 4)[0]
    if header[:2] == b'MZ' and len(header) == 64:
        offset = struct.unpack_from('<I', header, 60)[0]
        if not 64 <= offset <= 1_048_576:
            raise ValueError('PE header offset is outside the bounded inspection range')
        stream.seek(offset)
        pe = stream.read(6)
        if len(pe) != 6 or pe[:4] != b'PE\0\0':
            raise ValueError('missing PE signature')
        return 'pe', struct.unpack_from('<H', pe, 4)[0]
    raise ValueError('unrecognized ELF/PE/Mach-O header')


def check_archive(path, platforms=None):
    selected = list(platforms or PLATFORMS)
    errors, results = [], {}
    with zipfile.ZipFile(path) as archive:
        counts = Counter(info.filename for info in archive.infolist())
        for platform in selected:
            _, expected_format, expected_machine = PLATFORMS[platform]
            libraries = []
            for module, entry in required_entries(platform).items():
                record = {'module': module, 'entry': entry, 'valid': False}
                if counts[entry] != 1:
                    record['error'] = 'missing library' if not counts[entry] else 'duplicate library entries'
                elif archive.getinfo(entry).file_size == 0:
                    record['error'] = 'empty library'
                else:
                    try:
                        with archive.open(entry) as stream:
                            actual_format, machine = binary_architecture(stream)
                        record.update(format=actual_format, machine=machine)
                        if (actual_format, machine) != (expected_format, expected_machine):
                            record['error'] = f'expected {expected_format} machine {expected_machine}, found {actual_format} machine {machine}'
                        else:
                            record['valid'] = True
                    except (ValueError, struct.error, OSError, EOFError, zipfile.BadZipFile) as error:
                        record['error'] = str(error)
                if not record['valid']:
                    errors.append(f'{entry}: {record["error"]}')
                libraries.append(record)
            results[platform] = libraries
    return {'valid': not errors, 'staticOnly': True, 'archive': str(Path(path).resolve()),
            'platforms': results, 'errors': errors, 'nativeLibrariesLoaded': False,
            'runtimeCompatibilityVerified': False,
            'scope': 'Required archive entries and 64-bit ELF/PE/Mach-O architecture headers; does not verify system libraries, drivers, GPU behavior, signing, or platform startup.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    parser.add_argument('--platform', action='append', choices=PLATFORMS,
                        help='Check one packaged platform; omitted means all five desktop platforms.')
    parser.add_argument('--output', type=Path, help='Write detailed evidence to a new JSON file.')
    args = parser.parse_args()
    result = check_archive(args.archive, args.platform)
    if args.output:
        with args.output.open('x', encoding='utf-8') as output:
            json.dump(result, output, indent=2)
    print(json.dumps({key: value for key, value in result.items() if key != 'platforms'} |
                     {'checkedLibraries': {platform: len(rows) for platform, rows in result['platforms'].items()}}, indent=2))
    return 0 if result['valid'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
