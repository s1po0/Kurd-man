#!/usr/bin/env python3
"""Offline native design resource checks; not a device/accessibility certification."""
from pathlib import Path
import xml.etree.ElementTree as E
ROOT=Path(__file__).resolve().parents[2]
RES=ROOT/'android/app/src/main/res'
A='{http://schemas.android.com/apk/res/android}'
def lum(h):
    vals=[int(h[x:x+2],16)/255 for x in (1,3,5)]
    vals=[v/12.92 if v<=.04045 else ((v+.055)/1.055)**2.4 for v in vals]
    return sum(a*b for a,b in zip(vals,(.2126,.7152,.0722)))
def contrast(a,b):
    lo,hi=sorted((lum(a),lum(b)));return (hi+.05)/(lo+.05)
keys=None
for folder in ('values','values-en','values-ar'):
    names={e.get('name') for e in E.parse(RES/folder/'ux_strings.xml').getroot()}
    if keys is None:keys=names
    assert names==keys,'Reading-studio locale resource parity: '+folder
for p in RES.glob('layout/*.xml'):
    root=E.parse(p).getroot()
    assert all(A+'style'not in e.attrib for e in root.iter()),'style must be unqualified: '+str(p)
for folder in ('values','values-night'):
    colors={e.get('name'):e.text for e in E.parse(ROOT/'android/app/src/koorix/res'/folder/'colors.xml').getroot()}
    for ink,bg,target in [('text_primary','page',4.5),('text_secondary','card',4.5),('text_muted','card',4.5),('text_on_primary','primary',4.5),('text_on_primary','primary_dark',4.5),('primary','card',3),('border_dark','input_bg',3)]:
        actual=contrast(colors[ink],colors[bg]);assert actual>=target,(folder,ink,bg,actual)
print('Native reading studio: 3-locale copy parity, XML styles and measured semantic light/dark contrast pairs passed; not physical-device or TalkBack proof.')
