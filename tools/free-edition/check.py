#!/usr/bin/env python3
"""Release contracts for the owner's explicitly free public edition.
No service/account/data mutation, network, private credentials or signing recovery.
"""
from pathlib import Path
import re,xml.etree.ElementTree as E
ROOT=Path(__file__).resolve().parents[2]
ANDROID=ROOT/'android/app'
public=ANDROID/'src/koorix'
retired=('AccountGate','PremiumActivity','SupabaseAuth','DeviceAllowance','DeviceStateStore')
for name in retired:
    assert not (public/'java/com/kurdman/app'/f'{name}.java').exists(),name+' remains in public runtime'
for name in ('MainActivity','SavedJobCoordinator','SavedJobInput'):
    code=(ANDROID/'src/main/java/com/kurdman/app'/f'{name}.java').read_text()
    assert all(word not in code for word in retired),'Retired client referenced by '+name
runner=(ANDROID/'src/main/java/com/kurdman/app/SavedJobCoordinator.java').read_text()
assert 'CountDownLatch' not in runner and 'authorize(' not in runner and 'rel_check_allowance' not in runner
build=(ANDROID/'build.gradle').read_text().split('        koorix {',1)[1].split('    buildTypes {',1)[0]
for key in ('FREE_DAILY_JOBS','FREE_DAILY_PAGES'):
    assert re.search(r'"'+key+r'",\s*"0"',build),key
assert '"FREE_EDITION", "true"' in build and 'SUPABASE_' not in build
manifest=E.parse(public/'AndroidManifest.xml').getroot()
assert not manifest.findall('.//activity'),'Public billing/auth activity remains'
root=E.parse(ANDROID/'src/main/res/layout/activity_main.xml').getroot()
ns='{http://schemas.android.com/apk/res/android}'
ids={node.get(ns+'id','') for node in root.iter()}
assert '@+id/accountButton' not in ids
for retired_id in ('pickGalleryButton','pickFilesButton','inputSourceButtons'):
    assert '@+id/'+retired_id not in ids,'Split input UI remains: '+retired_id
for name in ('freeEditionNote','pickButton','inputBrowseHint','projectLibraryButton'):
    assert '@+id/'+name in ids,name
main=(ANDROID/'src/main/java/com/kurdman/app/MainActivity.java').read_text()
assert 'new ComicInputPicker.Sources()' in main and 'if(BuildConfig.PUBLIC_EDITION)openInputSources()' in main
assert 'PickMultipleVisualMedia' not in main and 'ComicInputPicker.choose(' not in main
picker=(ANDROID/'src/main/java/com/kurdman/app/ComicInputPicker.java').read_text()
assert 'new Intent(Intent.ACTION_GET_CONTENT)' in picker and '.setType("*/*")' in picker
assert 'Intent.createChooser(content(),' in picker
assert 'Intent.ACTION_OPEN_DOCUMENT' not in picker and 'EXTRA_MIME_TYPES' not in picker and 'EXTRA_INITIAL_URI' not in picker
keys=None
for p in (ANDROID/'src/main/res').glob('values*/free_picker_strings.xml'):
    names={e.attrib['name'] for e in E.parse(p).getroot().findall('string')}
    if keys is None:keys=names
    assert names==keys,'Missing translated picker/free-edition labels: '+str(p)
for p in (ANDROID/'src/main/res').glob('values*/strings.xml'):
    assert 'ks_0639' not in p.read_text(),'Retired account/Premium label remains'
assert '300L * 1024L * 1024L' in main and '1024L * 1024L * 1024L' in main
assert 'requestedCount - 300' in main
permissions={e.get(ns+'name','') for e in E.parse(ANDROID/'src/main/AndroidManifest.xml').getroot().findall('uses-permission')}
assert not permissions.intersection({'android.permission.MANAGE_EXTERNAL_STORAGE','android.permission.READ_EXTERNAL_STORAGE','android.permission.READ_MEDIA_IMAGES','android.permission.READ_MEDIA_VISUAL_USER_SELECTED'})

assert len(list((ANDROID/'src/main/res').glob('values*/free_picker_strings.xml')))==3
print('Free public edition contracts passed: no billing/registration/allowance dependency; one app-capable unfiltered source chooser wired; technical limits retained.')
