package com.kurdman.app;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;

/**
 * Offline identity comparison groundwork only: not an APK verifier, installer,
 * sandbox, consent flow or source runtime. Callers must independently obtain
 * validated package metadata and verified signing certificates from Android.
 */
final class ExtensionIdentityPolicy {
    enum Result { MATCH, INVALID_METADATA, PACKAGE_MISMATCH, VERSION_MISMATCH,
        UNSUPPORTED_LIBRARY, INVALID_SIGNER, UNAPPROVED_SIGNER, INSTALLED_IDENTITY_MISMATCH, DOWNGRADE }
    private ExtensionIdentityPolicy() { }

    /**
     * Compares an independently approved store signer against a candidate's
     * independently verified, single current signing certificate. Signed
     * metadata alone is never proof of safety. Unknown signer rotation and
     * multiple signers are deliberately rejected until explicitly supported.
     */
    static Result check(String expectedPackage,long expectedVersion,String library,
                        String approvedSha256,String actualPackage,long actualVersion,
                        List<byte[]> verifiedCurrentCertificates,
                        String installedPackage,long installedVersion,List<byte[]> installedCurrentCertificates){
        if(!validPackage(expectedPackage)||!validPackage(actualPackage)||expectedVersion<=0||actualVersion<=0
                ||!validFingerprint(approvedSha256)||library==null)return Result.INVALID_METADATA;
        if(!expectedPackage.equals(actualPackage))return Result.PACKAGE_MISMATCH;
        if(expectedVersion!=actualVersion)return Result.VERSION_MISMATCH;
        if(!"1.4".equals(library)&&!"1.6".equals(library))return Result.UNSUPPORTED_LIBRARY;
        byte[] candidate=digestOnlySigner(verifiedCurrentCertificates);
        if(candidate==null)return Result.INVALID_SIGNER;
        byte[] approved=decodeFingerprint(approvedSha256);
        if(!MessageDigest.isEqual(approved,candidate))return Result.UNAPPROVED_SIGNER;
        boolean hasInstalled=installedPackage!=null||installedCurrentCertificates!=null||installedVersion!=0;
        if(hasInstalled){
            if(!validPackage(installedPackage)||installedVersion<=0||!installedPackage.equals(actualPackage))
                return Result.INSTALLED_IDENTITY_MISMATCH;
            byte[] prior=digestOnlySigner(installedCurrentCertificates);
            if(prior==null||!MessageDigest.isEqual(prior,candidate))return Result.INSTALLED_IDENTITY_MISMATCH;
            if(actualVersion<installedVersion)return Result.DOWNGRADE;
        }
        return Result.MATCH;
    }
    private static boolean validPackage(String value){
        return value!=null && value.length()<=255 && value.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+");
    }
    private static boolean validFingerprint(String value){return value!=null&&value.matches("(?i)[0-9a-f]{64}");}
    private static byte[] decodeFingerprint(String value){
        String hex=value.toLowerCase(Locale.ROOT);byte[] result=new byte[32];
        for(int i=0;i<result.length;i++)result[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);
        return result;
    }
    private static byte[] digestOnlySigner(List<byte[]> certificates){
        if(certificates==null||certificates.size()!=1||certificates.get(0)==null
                ||certificates.get(0).length==0||certificates.get(0).length>65536)return null;
        try{return MessageDigest.getInstance("SHA-256").digest(certificates.get(0));}
        catch(NoSuchAlgorithmException impossible){throw new AssertionError("SHA-256 unavailable",impossible);}
    }
}
