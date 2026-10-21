"""Verify UTF-8 folding by unfolding the manifest and reading the original values."""
import importlib.util
from pathlib import Path

root = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('builder', root / 'tools/build_plugin.py')
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)
text = builder.manifest_text()
assert text.endswith('\r\n\r\n')
lines = text.split('\r\n')
assert all(len(line.encode('utf-8')) <= 70 for line in lines)
unfolded = text.replace('\r\n ', '')
assert 'Plugin-Description: Salt Player for Windows 的听歌时长、年度足迹与歌曲排行统计\r\n' in unfolded
assert f'Plugin-Version: {builder.PLUGIN_VERSION}\r\n' in unfolded
assert 'Plugin-Name: 听歌统计\r\n' in unfolded
print('PASS UTF-8 byte limits, continuation roundtrip, CRLF and plugin fields')
