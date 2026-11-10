#!/usr/bin/env python3
"""Offline native design resource checks; not a device/accessibility certification."""
from pathlib import Path
import xml.etree.ElementTree as E
import colorsys
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
    for ink,bg,target in [('text_primary','page',4.5),('text_secondary','card',4.5),('text_muted','card',4.5),('text_on_primary','primary',4.5),('text_on_primary','primary_dark',4.5),('primary','card',3),('primary','ui_tint',4.5),('text_secondary','card_soft',4.5),('text_secondary','ui_tint',4.5),('border_dark','input_bg',3)]:
        actual=contrast(colors[ink],colors[bg]);assert actual>=target,(folder,ink,bg,actual)
    accents=['ui_brand_blue','ui_brand_violet','ui_brand_peach']
    hue=[]
    for name in accents:
        h=colors[name];rgb=[int(h[x:x+2],16)/255 for x in (1,3,5)];hh,ss,vv=colorsys.rgb_to_hsv(*rgb)
        hue.append(hh*360);assert not 70<=hh*360<=180 and ss<.72,(folder,name,'green or fluorescent accent')
    for i in range(3):
        for j in range(i+1,3):
            gap=abs(hue[i]-hue[j]);assert min(gap,360-gap)>=18,(folder,'accents not distinct')
    for ink,bg,target in [('text_secondary','ui_blue_tint',4.5),('text_secondary','ui_violet_tint',4.5),('text_secondary','ui_peach_tint',4.5),('ui_peach_ink','ui_peach_tint',4.5),('ui_violet_ink','ui_violet_tint',4.5),('ui_overlay_text','ui_overlay_accent',4.5)]:
        assert contrast(colors[ink],colors[bg])>=target,(folder,ink,bg,contrast(colors[ink],colors[bg]))
header=next(e for e in E.parse(RES/'layout/activity_main.xml').getroot().iter()if e.get(A+'id')=='@+id/workspaceHeader')
assert any(e.get(A+'id')=='@+id/quickThemeButton'for e in header),'Dark mode shortcut must be in the persistent header'
hero=E.parse(RES/'drawable/bg_hero.xml').getroot().find('gradient')
assert hero is not None
assert {hero.get(A+x)for x in ('startColor','centerColor','endColor')}=={'@color/ui_blue_tint','@color/ui_violet_tint','@color/ui_peach_tint'},'Hero must blend the three actual brand hues'

print('Native triad reading studio: 3-locale copy parity, XML styles and measured semantic light/dark contrast pairs passed; not physical-device or TalkBack proof.')
