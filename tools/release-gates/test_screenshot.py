import importlib.util
from pathlib import Path
import struct
import unittest
import zlib
spec=importlib.util.spec_from_file_location('screenshot',Path(__file__).with_name('check_screenshot.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)

def png(blank=False,filter_type=0):
    w=h=64
    def chunk(tag,data):return struct.pack('!I',len(data))+tag+data+struct.pack('!I',zlib.crc32(tag+data)&0xffffffff)
    rows=[];prev=bytes(w*3)
    for y in range(h):
        row=bytes([0 if blank else (x*3+y)%256 for x in range(w*3)])
        if filter_type==1:encoded=bytes((v-(row[i-3] if i>=3 else 0))&255 for i,v in enumerate(row))
        elif filter_type==2:encoded=bytes((v-prev[i])&255 for i,v in enumerate(row))
        else:encoded=row
        rows.append(bytes([filter_type])+encoded);prev=row
    return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('!IIBBBBB',w,h,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(b''.join(rows)))+chunk(b'IEND',b'')

class ScreenshotTest(unittest.TestCase):
    def test_visible_content(self):self.assertEqual(m.check_png(png()),(64,64))
    def test_sub_filter(self):self.assertEqual(m.check_png(png(filter_type=1)),(64,64))
    def test_up_filter(self):self.assertEqual(m.check_png(png(filter_type=2)),(64,64))
    def test_black_rejected(self):
        with self.assertRaisesRegex(ValueError,'Blank'):m.check_png(png(blank=True))
    def test_corrupt_rejected(self):
        with self.assertRaises(ValueError):m.check_png(png()[:-8])
    def test_wrong_format_rejected(self):
        with self.assertRaises(ValueError):m.check_png(b'not png')
