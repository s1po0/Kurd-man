package com.kurdman.app;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** Synthetic bytes stand in for certificates; no APK signature verification is performed. */
public class ExtensionIdentityPolicyTest {
    private static final String SHA_A="ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb";
    private static List<byte[]> signer(String value){return Collections.singletonList(value.getBytes(StandardCharsets.UTF_8));}
    private static ExtensionIdentityPolicy.Result check(String pkg,long code,String lib,String approved,String actual,long actualCode,
                                                         List<byte[]> signer,String oldPkg,long oldCode,List<byte[]> oldSigner){
        return ExtensionIdentityPolicy.check(pkg,code,lib,approved,actual,actualCode,signer,oldPkg,oldCode,oldSigner);
    }
    @Test public void acceptsOnlyMatchingApprovedIdentityAndSameSignerUpdate(){
        assertEquals(ExtensionIdentityPolicy.Result.MATCH,check("org.example.ext",5,"1.6",SHA_A,"org.example.ext",5,signer("a"),null,0,null));
        assertEquals(ExtensionIdentityPolicy.Result.MATCH,check("org.example.ext",5,"1.4",SHA_A.toUpperCase(),"org.example.ext",5,signer("a"),"org.example.ext",4,signer("a")));
    }
    @Test public void rejectsSubstitutionDowngradeAndSignerRotation(){
        assertEquals(ExtensionIdentityPolicy.Result.PACKAGE_MISMATCH,check("org.example.ext",5,"1.6",SHA_A,"org.example.bad",5,signer("a"),null,0,null));
        assertEquals(ExtensionIdentityPolicy.Result.VERSION_MISMATCH,check("org.example.ext",5,"1.6",SHA_A,"org.example.ext",6,signer("a"),null,0,null));
        assertEquals(ExtensionIdentityPolicy.Result.UNAPPROVED_SIGNER,check("org.example.ext",5,"1.6",SHA_A,"org.example.ext",5,signer("b"),null,0,null));
        assertEquals(ExtensionIdentityPolicy.Result.INSTALLED_IDENTITY_MISMATCH,check("org.example.ext",5,"1.6",SHA_A,"org.example.ext",5,signer("a"),"org.example.ext",4,signer("b")));
        assertEquals(ExtensionIdentityPolicy.Result.DOWNGRADE,check("org.example.ext",5,"1.6",SHA_A,"org.example.ext",5,signer("a"),"org.example.ext",6,signer("a")));
    }
    @Test public void rejectsInvalidOrAmbiguousMetadata(){
        assertEquals(ExtensionIdentityPolicy.Result.INVALID_METADATA,check("org.example.ext",5,"1.6","bad","org.example.ext",5,signer("a"),null,0,null));
        assertEquals(ExtensionIdentityPolicy.Result.UNSUPPORTED_LIBRARY,check("org.example.ext",5,"1.9",SHA_A,"org.example.ext",5,signer("a"),null,0,null));
        assertEquals(ExtensionIdentityPolicy.Result.INVALID_SIGNER,check("org.example.ext",5,"1.6",SHA_A,"org.example.ext",5,Collections.emptyList(),null,0,null));
        assertEquals(ExtensionIdentityPolicy.Result.INVALID_SIGNER,check("org.example.ext",5,"1.6",SHA_A,"org.example.ext",5,Arrays.asList(new byte[]{1},new byte[]{2}),null,0,null));
        assertEquals(ExtensionIdentityPolicy.Result.INSTALLED_IDENTITY_MISMATCH,check("org.example.ext",5,"1.6",SHA_A,"org.example.ext",5,signer("a"),"org.example.ext",0,null));
    }
}
