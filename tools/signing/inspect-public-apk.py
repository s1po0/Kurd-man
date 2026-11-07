#!/usr/bin/env python3
"""Independently verify a PUBLIC APK's v2 signature/digest/pinned certificate.

Reads the APK and PUBLIC pin JSON only. Never opens or restores a keystore.
Also checks the binary manifest, CRCs, regular-app classes and absence of test
harnesses/private recovery files. This is not device/translation quality proof.
"""
import argparse,hashlib,hmac,json,struct,zipfile
from pathlib import Path
from cryptography import x509
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import padding,rsa

U32=lambda data,at=0:struct.unpack_from('<I',data,at)[0]
U64=lambda data,at=0:struct.unpack_from('<Q',data,at)[0]

def lp(data,at=0):
    size=U32(data,at);start=at+4;end=start+size
    if end>len(data):raise ValueError('Truncated length-prefixed signature field')
    return data[start:end],end

def records(data):
    at=0
    while at<len(data):
        record,at=lp(data,at);yield record

def verify_v2(data,pins):
    eocd=data.rfind(b'PK\x05\x06',max(0,len(data)-65557))
    if eocd<0 or eocd+22+struct.unpack_from('<H',data,eocd+20)[0]!=len(data):raise ValueError('Invalid ZIP footer')
    central=U32(data,eocd+16)
    if data[central-16:central]!=b'APK Sig Block 42':raise ValueError('Missing APK signing block')
    size=U64(data,central-24);start=central-size-8
    if start<0 or U64(data,start)!=size:raise ValueError('Invalid signing block length')
    blocks={};at=start+8
    while at<central-24:
        length=U64(data,at);at+=8
        if length<4 or at+length>central-24:raise ValueError('Invalid APK signature pair')
        kind=U32(data,at);blocks[kind]=data[at+4:at+length];at+=length
    if 0x7109871a not in blocks:raise ValueError('APK v2 signature missing')
    signers,end=lp(blocks[0x7109871a]);assert end==len(blocks[0x7109871a])
    signers=list(records(signers));assert len(signers)==1,'Unexpected number of signers'
    signer=signers[0];signed,at=lp(signer);signatures,at=lp(signer,at);public_der,at=lp(signer,at);assert at==len(signer)
    digests,at=lp(signed);certificates,at=lp(signed,at);attributes,at=lp(signed,at)
    # Android's apksig writer can include its signed, zero-length trailing field.
    assert signed[at:] in (b'',b'\0\0\0\0'),'Unexpected signed-data trailer'
    certs=list(records(certificates));assert certs
    certificate=x509.load_der_x509_certificate(certs[0]);fingerprint=certificate.fingerprint(hashes.SHA256()).hex()
    assert hmac.compare_digest(fingerprint,pins['signer_sha256']),'Wrong production certificate'
    key=certificate.public_key();assert isinstance(key,rsa.RSAPublicKey) and key.key_size==3072
    assert key.public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo)==public_der,'Certificate/public-key mismatch'
    signatures={U32(record):lp(record,4)[0] for record in records(signatures)}
    digests={U32(record):lp(record,4)[0] for record in records(digests)}
    algorithm=next((a for a in (0x0103,0x0101,0x0104,0x0102) if a in signatures and a in digests),None)
    assert algorithm is not None,'Unsupported signature algorithm'
    sha512=algorithm in (0x0104,0x0102);crypto_hash=hashes.SHA512() if sha512 else hashes.SHA256()
    signature_padding=padding.PSS(mgf=padding.MGF1(crypto_hash),salt_length=crypto_hash.digest_size) if algorithm in (0x0101,0x0102) else padding.PKCS1v15()
    key.verify(signatures[algorithm],signed,signature_padding,crypto_hash)
    footer=bytearray(data[eocd:]);struct.pack_into('<I',footer,16,start)
    chunks=[];hash_fn=hashlib.sha512 if sha512 else hashlib.sha256
    for section in (data[:start],data[central:eocd],bytes(footer)):
        for offset in range(0,len(section),1024*1024):
            chunk=section[offset:offset+1024*1024];chunks.append(hash_fn(b'\xa5'+struct.pack('<I',len(chunk))+chunk).digest())
    actual=hash_fn(b'\x5a'+struct.pack('<I',len(chunks))+b''.join(chunks)).digest()
    assert hmac.compare_digest(actual,digests[algorithm]),'APK content digest mismatch'
    return {'scheme':'v2','rsa_bits':key.key_size,'certificate_sha256':fingerprint,'algorithm':hex(algorithm),'content_digest_verified':True}

def pool(data,at):
    header=struct.unpack_from('<H',data,at+2)[0];count=U32(data,at+8);flags=U32(data,at+16);strings=U32(data,at+20)
    def length8(pos):
        first=data[pos];pos+=1
        if first&128:return ((first&127)<<8)|data[pos],pos+1
        return first,pos
    def length16(pos):
        first=struct.unpack_from('<H',data,pos)[0];pos+=2
        if first&32768:return ((first&32767)<<16)|struct.unpack_from('<H',data,pos)[0],pos+2
        return first,pos
    values=[]
    for index in range(count):
        pos=at+strings+U32(data,at+header+4*index)
        if flags&256:
            _,pos=length8(pos);size,pos=length8(pos);value=data[pos:pos+size].decode('utf-8')
        else:
            size,pos=length16(pos);value=data[pos:pos+2*size].decode('utf-16-le')
        values.append(value)
    return values

def manifest(data):
    strings=[];elements=[];at=struct.unpack_from('<H',data,2)[0]
    while at<len(data):
        kind,header,size=struct.unpack_from('<HHI',data,at)
        if not size or at+size>len(data):raise ValueError('Invalid binary XML chunk')
        if kind==1:strings=pool(data,at)
        if kind==0x0102:
            name=strings[U32(data,at+20)];attribute_start,attribute_size,count=struct.unpack_from('<HHH',data,at+24);attrs={}
            pos=at+16+attribute_start
            for _ in range(count):
                key=strings[U32(data,pos+4)];raw=U32(data,pos+8);value_type=data[pos+15];value=U32(data,pos+16)
                if value_type==3:value=strings[value]
                elif value_type==0x12:value=bool(value)
                elif raw!=0xffffffff:value=strings[raw]
                attrs[key]=value;pos+=attribute_size
            elements.append((name,attrs))
        at+=size
    return elements

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('apk');parser.add_argument('--pins',default='signing/koorix-release-v2.json');parser.add_argument('--version',required=True);parser.add_argument('--code',type=int,required=True);parser.add_argument('--no-login',action='store_true');parser.add_argument('--free',action='store_true');parser.add_argument('--contours',action='store_true');args=parser.parse_args()
    path=Path(args.apk);data=path.read_bytes();pins=json.loads(Path(args.pins).read_text());signature=verify_v2(data,pins)
    with zipfile.ZipFile(path) as archive:
        assert archive.testzip() is None,'APK CRC mismatch';names=archive.namelist()
        assert not any(n.lower().endswith(('.p12','.jks','.keystore','.pkcs12')) or 'signing-recovery' in n.lower() or 'owner-recovery' in n.lower() for n in names),'Private recovery material found'
        elements=manifest(archive.read('AndroidManifest.xml'));package=next(a for n,a in elements if n=='manifest');app=next(a for n,a in elements if n=='application')
        assert package['package']==pins['application_id']=='com.koorix.app'
        assert package['versionName']==args.version and package['versionCode']==args.code
        assert app.get('debuggable',False) is False and app.get('label')=='Koorix','Not a regular non-debuggable Koorix'
        dex=b''.join(archive.read(n) for n in names if n.startswith('classes') and n.endswith('.dex'))
        for forbidden in (b'Lorg/robolectric/',b'Lorg/junit/',b'Lcom/kurdman/app/KoorixReliabilityTest;'):
            assert forbidden not in dex,'Unit-test harness present in APK'
        if args.no_login:
            assert not any(n=='activity' and str(a.get('name','')).endswith('.AccountActivity') for n,a in elements)
            assert not any(n=='data' and a.get('scheme')=='com.koorix.app' and a.get('host')=='auth' for n,a in elements)
            assert b'Lcom/kurdman/app/AccountActivity;' not in dex
            for required in (('SavedJobCoordinator','ProjectLibraryActivity','ComicTranslateControls','ComicInputPicker') if args.free else ('PremiumActivity','DeviceStateStore','SavedJobCoordinator','ProjectLibraryActivity','ComicTranslateControls')):
                assert ('Lcom/kurdman/app/'+required+';').encode() in dex,'Missing native class '+required
        if args.free:
            for forbidden in ('AccountGate','PremiumActivity','SupabaseAuth','DeviceAllowance','DeviceStateStore'):
                assert ('Lcom/kurdman/app/'+forbidden+';').encode() not in dex,'Retired billing client packaged: '+forbidden
                assert not any(n=='activity' and str(a.get('name','')).endswith('.'+forbidden) for n,a in elements),'Retired billing UI registered'
            assert b'cbkddsztcivllpwzflkx.supabase.co' not in dex,'Entitlement server remains in free runtime'
            assert b'sb_publishable_' not in dex,'Entitlement key remains in free runtime'
        if args.contours:
            for required in ('BubbleBoundary','StyledTextRenderer','PageEditorView'):
                assert ('Lcom/kurdman/app/'+required+';').encode() in dex,'Missing native contour class '+required
            for marker in (b'placementAnchor',b'clipForErase',b'drawUnclipped',b'bubbleBoundary'):
                assert marker in dex,'Missing native contour implementation marker'
            assert b'Lcom/kurdman/app/BubbleContainmentAndroidTest;' not in dex,'Synthetic glyph harness packaged in APK'
        result={'file':path.name,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest(),'zip_entries':len(names),'signature':signature,'package':package['package'],'version':package['versionName'],'version_code':package['versionCode'],'label':app['label'],'debuggable':False,'no_login_gate_checked':args.no_login,'free_edition_checked':args.free,'test_harness_and_private_recovery_absent':True,'native_contour_classes_checked':args.contours}
    print(json.dumps(result,indent=2))
if __name__=='__main__':main()
