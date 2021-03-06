package org.spongycastle.openpgp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import org.spongycastle.asn1.x9.ECNamedCurveTable;
import org.spongycastle.bcpg.BCPGInputStream;
import org.spongycastle.bcpg.BCPGObject;
import org.spongycastle.bcpg.BCPGOutputStream;
import org.spongycastle.bcpg.ContainedPacket;
import org.spongycastle.bcpg.DSASecretBCPGKey;
import org.spongycastle.bcpg.ECDSAPublicBCPGKey;
import org.spongycastle.bcpg.ECSecretBCPGKey;
import org.spongycastle.bcpg.ElGamalSecretBCPGKey;
import org.spongycastle.bcpg.HashAlgorithmTags;
import org.spongycastle.bcpg.PublicKeyAlgorithmTags;
import org.spongycastle.bcpg.PublicKeyPacket;
import org.spongycastle.bcpg.RSASecretBCPGKey;
import org.spongycastle.bcpg.S2K;
import org.spongycastle.bcpg.SecretKeyPacket;
import org.spongycastle.bcpg.SecretSubkeyPacket;
import org.spongycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.spongycastle.bcpg.UserAttributePacket;
import org.spongycastle.bcpg.UserIDPacket;
import org.spongycastle.openpgp.operator.KeyFingerPrintCalculator;
import org.spongycastle.openpgp.operator.PBEProtectionRemoverFactory;
import org.spongycastle.openpgp.operator.PBESecretKeyDecryptor;
import org.spongycastle.openpgp.operator.PBESecretKeyEncryptor;
import org.spongycastle.openpgp.operator.PGPContentSignerBuilder;
import org.spongycastle.openpgp.operator.PGPDigestCalculator;

/**
 * general class to handle and construct  a PGP secret key object.
 */
public class PGPSecretKey
{    
    SecretKeyPacket secret;
    PGPPublicKey    pub;

    PGPSecretKey(
        SecretKeyPacket secret,
        PGPPublicKey    pub)
    {
        this.secret = secret;
        this.pub = pub;
    }
    
    PGPSecretKey(
        PGPPrivateKey   privKey,
        PGPPublicKey    pubKey,
        PGPDigestCalculator checksumCalculator,
        PBESecretKeyEncryptor keyEncryptor)
        throws PGPException
    {
        this(privKey, pubKey, checksumCalculator, false, keyEncryptor);
    }

    /**
     * Construct a PGPSecretKey using the passed in private key and public key. This constructor will not add any
     * certifications but assumes that pubKey already has what is required.
     *
     * @param privKey the private key component.
     * @param pubKey the public key component.
     * @param checksumCalculator a calculator for the private key checksum
     * @param isMasterKey true if the key is a master key, false otherwise.
     * @param keyEncryptor an encryptor for the key if required (null otherwise).
     * @throws PGPException if there is an issue creating the secret key packet.
     */
    public PGPSecretKey(
        PGPPrivateKey   privKey,
        PGPPublicKey    pubKey,
        PGPDigestCalculator checksumCalculator,
        boolean         isMasterKey,
        PBESecretKeyEncryptor keyEncryptor)
        throws PGPException
    {
        this.pub = pubKey;
        this.secret = buildSecretKeyPacket(isMasterKey, privKey, pubKey, keyEncryptor, checksumCalculator);
    }

    private static SecretKeyPacket buildSecretKeyPacket(boolean isMasterKey, PGPPrivateKey privKey, PGPPublicKey pubKey, PBESecretKeyEncryptor keyEncryptor, PGPDigestCalculator checksumCalculator)
        throws PGPException
    {
        BCPGObject secKey = (BCPGObject)privKey.getPrivateKeyDataPacket();

        if (secKey == null)
        {
            if (isMasterKey)
            {
                return new SecretKeyPacket(pubKey.publicPk, SymmetricKeyAlgorithmTags.NULL, null, null, new byte[0]);
            }
            else
            {
                return new SecretSubkeyPacket(pubKey.publicPk, SymmetricKeyAlgorithmTags.NULL, null, null, new byte[0]);
            }
        }

        try
        {
            ByteArrayOutputStream bOut = new ByteArrayOutputStream();
            BCPGOutputStream pOut = new BCPGOutputStream(bOut);

            pOut.writeObject(secKey);

            byte[]    keyData = bOut.toByteArray();

            pOut.write(checksum(checksumCalculator, keyData, keyData.length));

            int encAlgorithm = (keyEncryptor != null) ? keyEncryptor.getAlgorithm() : SymmetricKeyAlgorithmTags.NULL;

            if (encAlgorithm != SymmetricKeyAlgorithmTags.NULL)
            {
                keyData = bOut.toByteArray(); // include checksum

                byte[] encData = keyEncryptor.encryptKeyData(keyData, 0, keyData.length);
                byte[] iv = keyEncryptor.getCipherIV();

                S2K s2k = keyEncryptor.getS2K();

                int s2kUsage;

                if (checksumCalculator != null)
                {
                    if (checksumCalculator.getAlgorithm() != HashAlgorithmTags.SHA1)
                    {
                        throw new PGPException("only SHA1 supported for key checksum calculations.");
                    }
                    s2kUsage = SecretKeyPacket.USAGE_SHA1;
                }
                else
                {
                    s2kUsage = SecretKeyPacket.USAGE_CHECKSUM;
                }

                if (isMasterKey)
                {
                    return new SecretKeyPacket(pubKey.publicPk, encAlgorithm, s2kUsage, s2k, iv, encData);
                }
                else
                {
                    return new SecretSubkeyPacket(pubKey.publicPk, encAlgorithm, s2kUsage, s2k, iv, encData);
                }
            }
            else
            {
                if (isMasterKey)
                {
                    return new SecretKeyPacket(pubKey.publicPk, encAlgorithm, null, null, bOut.toByteArray());
                }
                else
                {
                    return new SecretSubkeyPacket(pubKey.publicPk, encAlgorithm, null, null, bOut.toByteArray());
                }
            }
        }
        catch (PGPException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new PGPException("Exception encrypting key", e);
        }
    }

    /**
     * Construct a PGPSecretKey using the passed in private/public key pair and binding it to the passed in id
     * using a generated certification of certificationLevel.The secret key checksum is calculated using the original
     * non-digest based checksum.
     *
     * @param certificationLevel the type of certification to be added.
     * @param keyPair the public/private keys to use.
     * @param id the id to bind to the key.
     * @param hashedPcks the hashed packets to be added to the certification.
     * @param unhashedPcks the unhashed packets to be added to the certification.
     * @param certificationSignerBuilder the builder for generating the certification.
     * @param keyEncryptor an encryptor for the key if required (null otherwise).
     * @throws PGPException if there is an issue creating the secret key packet or the certification.
     */
    public PGPSecretKey(
        int                         certificationLevel,
        PGPKeyPair                  keyPair,
        String                      id,
        PGPSignatureSubpacketVector hashedPcks,
        PGPSignatureSubpacketVector unhashedPcks,
        PGPContentSignerBuilder     certificationSignerBuilder,
        PBESecretKeyEncryptor       keyEncryptor)
        throws PGPException
    {
        this(certificationLevel, keyPair, id, null, hashedPcks, unhashedPcks, certificationSignerBuilder, keyEncryptor);
    }

    /**
     * Construct a PGPSecretKey using the passed in private/public key pair and binding it to the passed in id
     * using a generated certification of certificationLevel.
     *
     * @param certificationLevel the type of certification to be added.
     * @param keyPair the public/private keys to use.
     * @param id the id to bind to the key.
     * @param checksumCalculator a calculator for the private key checksum.
     * @param hashedPcks the hashed packets to be added to the certification.
     * @param unhashedPcks the unhashed packets to be added to the certification.
     * @param certificationSignerBuilder the builder for generating the certification.
     * @param keyEncryptor an encryptor for the key if required (null otherwise).
     * @throws PGPException if there is an issue creating the secret key packet or the certification.
     */
    public PGPSecretKey(
        int                         certificationLevel,
        PGPKeyPair                  keyPair,
        String                      id,
        PGPDigestCalculator         checksumCalculator,
        PGPSignatureSubpacketVector hashedPcks,
        PGPSignatureSubpacketVector unhashedPcks,
        PGPContentSignerBuilder     certificationSignerBuilder,
        PBESecretKeyEncryptor       keyEncryptor)
        throws PGPException
    {
        this(keyPair.getPrivateKey(), certifiedPublicKey(certificationLevel, keyPair, id, hashedPcks, unhashedPcks, certificationSignerBuilder), checksumCalculator, true, keyEncryptor);
    }

    private static PGPPublicKey certifiedPublicKey(
        int certificationLevel,
        PGPKeyPair keyPair,
        String id,
        PGPSignatureSubpacketVector hashedPcks,
        PGPSignatureSubpacketVector unhashedPcks,
        PGPContentSignerBuilder     certificationSignerBuilder)
        throws PGPException
    {
        PGPSignatureGenerator    sGen;

        try
        {
            sGen = new PGPSignatureGenerator(certificationSignerBuilder);
        }
        catch (Exception e)
        {
            throw new PGPException("creating signature generator: " + e, e);
        }

        //
        // generate the certification
        //
        sGen.init(certificationLevel, keyPair.getPrivateKey());

        sGen.setHashedSubpackets(hashedPcks);
        sGen.setUnhashedSubpackets(unhashedPcks);

        try
        {
            PGPSignature    certification = sGen.generateCertification(id, keyPair.getPublicKey());

            return PGPPublicKey.addCertification(keyPair.getPublicKey(), id, certification);
        }
        catch (Exception e)
        {
            throw new PGPException("exception doing certification: " + e, e);
        }
    }

    /**
     * Return true if this key has an algorithm type that makes it suitable to use for signing.
     * <p>
     * Note: with version 4 keys KeyFlags subpackets should also be considered when present for
     * determining the preferred use of the key.
     *
     * @return true if this key algorithm is suitable for use with signing.
     */
    public boolean isSigningKey()
    {
        int algorithm = pub.getAlgorithm();

        return ((algorithm == PGPPublicKey.RSA_GENERAL) || (algorithm == PGPPublicKey.RSA_SIGN)
                    || (algorithm == PGPPublicKey.DSA) || (algorithm == PGPPublicKey.ECDSA) || (algorithm == PGPPublicKey.ELGAMAL_GENERAL));
    }
    
    /**
     * Return true if this is a master key.
     * @return true if a master key.
     */
    public boolean isMasterKey()
    {
        return pub.isMasterKey();
    }

    /**
     * Detect if the Secret Key's Private Key is empty or not
     *
     * @return boolean whether or not the private key is empty
     */
    public boolean isPrivateKeyEmpty()
    {
        byte[] secKeyData = secret.getSecretKeyData();

        return (secKeyData == null || secKeyData.length < 1);
    }

    /**
     * return the algorithm the key is encrypted with.
     *
     * @return the algorithm used to encrypt the secret key.
     */
    public int getKeyEncryptionAlgorithm()
    {
        return secret.getEncAlgorithm();
    }

    /**
     * Return the keyID of the public key associated with this key.
     * 
     * @return the keyID associated with this key.
     */
    public long getKeyID()
    {
        return pub.getKeyID();
    }

    /**
     * Return the S2K usage associated with this key.
     *
     * @return the key's S2K usage
     */
    public int getS2KUsage()
    {
        return secret.getS2KUsage();
    }

    /**
     * Return the S2K used to process this key
     *
     * @return the key's S2K, null if one is not present.
     */
    public S2K getS2K()
    {
        return secret.getS2K();
    }

    /**
     * Return the public key associated with this key.
     * 
     * @return the public key for this key.
     */
    public PGPPublicKey getPublicKey()
    {
        return pub;
    }
    
    /**
     * Return any userIDs associated with the key.
     * 
     * @return an iterator of Strings.
     */
    public Iterator getUserIDs()
    {
        return pub.getUserIDs();
    }
    
    /**
     * Return the IV this secret key is encrypted with.
     *
     * @return the IV of this key.
     */
    public byte[] getIV() {
        return secret.getIV();
    }

    /**
     * Return any user attribute vectors associated with the key.
     * 
     * @return an iterator of PGPUserAttributeSubpacketVector.
     */
    public Iterator getUserAttributes()
    {
        return pub.getUserAttributes();
    }

    private byte[] extractKeyData(
        PBESecretKeyDecryptor decryptorFactory)
        throws PGPException
    {
        byte[] encData = secret.getSecretKeyData();
        byte[] data = null;

        if (secret.getEncAlgorithm() != SymmetricKeyAlgorithmTags.NULL)
        {
            try
            {
                if (secret.getPublicKeyPacket().getVersion() == 4)
                {
                    byte[] key = decryptorFactory.makeKeyFromPassPhrase(secret.getEncAlgorithm(), secret.getS2K());

                    data = decryptorFactory.recoverKeyData(secret.getEncAlgorithm(), key, secret.getIV(), encData, 0, encData.length);

                    boolean useSHA1 = secret.getS2KUsage() == SecretKeyPacket.USAGE_SHA1;
                    byte[] check = checksum(useSHA1 ? decryptorFactory.getChecksumCalculator(HashAlgorithmTags.SHA1) : null, data, (useSHA1) ? data.length - 20 : data.length - 2);

                    for (int i = 0; i != check.length; i++)
                    {
                        if (check[i] != data[data.length - check.length + i])
                        {
                            throw new PGPException("checksum mismatch at " + i + " of " + check.length);
                        }
                    }
                }
                else // version 2 or 3, RSA only.
                {
                    byte[] key = decryptorFactory.makeKeyFromPassPhrase(secret.getEncAlgorithm(), secret.getS2K());

                    data = new byte[encData.length];

                    byte[] iv = new byte[secret.getIV().length];

                    System.arraycopy(secret.getIV(), 0, iv, 0, iv.length);

                    //
                    // read in the four numbers
                    //
                    int pos = 0;

                    for (int i = 0; i != 4; i++)
                    {
                        int encLen = (((encData[pos] << 8) | (encData[pos + 1] & 0xff)) + 7) / 8;

                        data[pos] = encData[pos];
                        data[pos + 1] = encData[pos + 1];

                        byte[] tmp = decryptorFactory.recoverKeyData(secret.getEncAlgorithm(), key, iv, encData, pos + 2, encLen);
                        System.arraycopy(tmp, 0, data, pos + 2, tmp.length);
                        pos += 2 + encLen;

                        if (i != 3)
                        {
                            System.arraycopy(encData, pos - iv.length, iv, 0, iv.length);
                        }
                    }

                    //
                    // verify and copy checksum
                    //

                    data[pos] = encData[pos];
                    data[pos + 1] = encData[pos + 1];

                    int cs = ((encData[pos] << 8) & 0xff00) | (encData[pos + 1] & 0xff);
                    int calcCs = 0;
                    for (int j = 0; j < data.length - 2; j++)
                    {
                        calcCs += data[j] & 0xff;
                    }

                    calcCs &= 0xffff;
                    if (calcCs != cs)
                    {
                        throw new PGPException("checksum mismatch: passphrase wrong, expected "
                            + Integer.toHexString(cs)
                            + " found " + Integer.toHexString(calcCs));
                    }
                }
            }
            catch (PGPException e)
            {
                throw e;
            }
            catch (Exception e)
            {
                throw new PGPException("Exception decrypting key", e);
            }
        }
        else
        {
            data = encData;
        }

        return data;
    }

    /**
     * Extract a PGPPrivate key from the SecretKey's encrypted contents.
     *
     * @param decryptorFactory  factory to use to generate a decryptor for the passed in secretKey.
     * @return PGPPrivateKey  the unencrypted private key.
     * @throws PGPException on failure.
     */
    public  PGPPrivateKey extractPrivateKey(
        PBESecretKeyDecryptor decryptorFactory)
        throws PGPException
    {
        if (isPrivateKeyEmpty())
        {
            return null;
        }

        PublicKeyPacket pubPk = secret.getPublicKeyPacket();

        try
        {
            byte[]             data = extractKeyData(decryptorFactory);
            BCPGInputStream    in = new BCPGInputStream(new ByteArrayInputStream(data));


            switch (pubPk.getAlgorithm())
            {
            case PGPPublicKey.RSA_ENCRYPT:
            case PGPPublicKey.RSA_GENERAL:
            case PGPPublicKey.RSA_SIGN:
                RSASecretBCPGKey        rsaPriv = new RSASecretBCPGKey(in);

                return new PGPPrivateKey(this.getKeyID(), pubPk, rsaPriv);
            case PGPPublicKey.DSA:
                DSASecretBCPGKey    dsaPriv = new DSASecretBCPGKey(in);

                return new PGPPrivateKey(this.getKeyID(), pubPk, dsaPriv);
            case PGPPublicKey.ELGAMAL_ENCRYPT:
            case PGPPublicKey.ELGAMAL_GENERAL:
                ElGamalSecretBCPGKey    elPriv = new ElGamalSecretBCPGKey(in);

                return new PGPPrivateKey(this.getKeyID(), pubPk, elPriv);
            case PGPPublicKey.ECDH:
            case PGPPublicKey.ECDSA:
                ECSecretBCPGKey ecPriv = new ECSecretBCPGKey(in);

                return new PGPPrivateKey(this.getKeyID(), pubPk, ecPriv);
            default:
                throw new PGPException("unknown public key algorithm encountered");
            }
        }
        catch (PGPException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new PGPException("Exception constructing key", e);
        }
    }
    
    private static byte[] checksum(PGPDigestCalculator digCalc, byte[] bytes, int length)
        throws PGPException
    {
        if (digCalc != null)
        {
            OutputStream dOut = digCalc.getOutputStream();

            try
            {
                dOut.write(bytes, 0, length);

                dOut.close();
            }
            catch (Exception e)
            {
               throw new PGPException("checksum digest calculation failed: " + e.getMessage(), e);
            }
            return digCalc.getDigest();
        }
        else
        {
            int       checksum = 0;
        
            for (int i = 0; i != length; i++)
            {
                checksum += bytes[i] & 0xff;
            }
        
            byte[] check = new byte[2];

            check[0] = (byte)(checksum >> 8);
            check[1] = (byte)checksum;

            return check;
        }
    }
    
    public byte[] getEncoded() 
        throws IOException
    {
        ByteArrayOutputStream    bOut = new ByteArrayOutputStream();
        
        this.encode(bOut);
        
        return bOut.toByteArray();
    }
    
    public void encode(
        OutputStream    outStream) 
        throws IOException
    {
        BCPGOutputStream    out;
        
        if (outStream instanceof BCPGOutputStream)
        {
            out = (BCPGOutputStream)outStream;
        }
        else
        {
            out = new BCPGOutputStream(outStream);
        }

        out.writePacket(secret);
        if (pub.trustPk != null)
        {
            out.writePacket(pub.trustPk);
        }
        
        if (pub.subSigs == null)        // is not a sub key
        {
            for (int i = 0; i != pub.keySigs.size(); i++)
            {
                ((PGPSignature)pub.keySigs.get(i)).encode(out);
            }
            
            for (int i = 0; i != pub.ids.size(); i++)
            {
                if (pub.ids.get(i) instanceof UserIDPacket)
                {
                    UserIDPacket    id = (UserIDPacket)pub.ids.get(i);
                    
                    out.writePacket(id);
                }
                else
                {
                    PGPUserAttributeSubpacketVector    v = (PGPUserAttributeSubpacketVector)pub.ids.get(i);

                    out.writePacket(new UserAttributePacket(v.toSubpacketArray()));
                }
                
                if (pub.idTrusts.get(i) != null)
                {
                    out.writePacket((ContainedPacket)pub.idTrusts.get(i));
                }
                
                List         sigs = (ArrayList)pub.idSigs.get(i);
                
                for (int j = 0; j != sigs.size(); j++)
                {
                    ((PGPSignature)sigs.get(j)).encode(out);
                }
            }
        }
        else
        {        
            for (int j = 0; j != pub.subSigs.size(); j++)
            {
                ((PGPSignature)pub.subSigs.get(j)).encode(out);
            }
        }
    }

    /**
     * Return a copy of the passed in secret key, encrypted using a new
     * password and the passed in algorithm.
     *
     * @param key the PGPSecretKey to be copied.
     * @param oldKeyDecryptor the current decryptor based on the current password for key.
     * @param newKeyEncryptor a new encryptor based on a new password for encrypting the secret key material.
     */
    public static PGPSecretKey copyWithNewPassword(
        PGPSecretKey           key,
        PBESecretKeyDecryptor  oldKeyDecryptor,
        PBESecretKeyEncryptor  newKeyEncryptor)
        throws PGPException
    {
        if (key.isPrivateKeyEmpty())
        {
            throw new PGPException("no private key in this SecretKey - public key present only.");
        }

        byte[]     rawKeyData = key.extractKeyData(oldKeyDecryptor);
        int        s2kUsage = key.secret.getS2KUsage();
        byte[]      iv = null;
        S2K         s2k = null;
        byte[]      keyData;
        int         newEncAlgorithm = SymmetricKeyAlgorithmTags.NULL;

        if (newKeyEncryptor == null || newKeyEncryptor.getAlgorithm() == SymmetricKeyAlgorithmTags.NULL)
        {
            s2kUsage = SecretKeyPacket.USAGE_NONE;
            if (key.secret.getS2KUsage() == SecretKeyPacket.USAGE_SHA1)   // SHA-1 hash, need to rewrite checksum
            {
                keyData = new byte[rawKeyData.length - 18];

                System.arraycopy(rawKeyData, 0, keyData, 0, keyData.length - 2);

                byte[] check = checksum(null, keyData, keyData.length - 2);

                keyData[keyData.length - 2] = check[0];
                keyData[keyData.length - 1] = check[1];
            }
            else
            {
                keyData = rawKeyData;
            }
        }
        else
        {
            if (s2kUsage == SecretKeyPacket.USAGE_NONE)
            {
                s2kUsage = SecretKeyPacket.USAGE_CHECKSUM;
            }
            if (key.secret.getPublicKeyPacket().getVersion() < 4)
            {
                // Version 2 or 3 - RSA Keys only

                byte[] encKey = newKeyEncryptor.getKey();
                keyData = new byte[rawKeyData.length];

                if (newKeyEncryptor.getHashAlgorithm() != HashAlgorithmTags.MD5)
                {
                    throw new PGPException("MD5 Digest Calculator required for version 3 key encryptor.");
                }

                //
                // process 4 numbers
                //
                int pos = 0;
                for (int i = 0; i != 4; i++)
                {
                    int encLen = (((rawKeyData[pos] << 8) | (rawKeyData[pos + 1] & 0xff)) + 7) / 8;

                    keyData[pos] = rawKeyData[pos];
                    keyData[pos + 1] = rawKeyData[pos + 1];

                    byte[] tmp;
                    if (i == 0)
                    {
                        tmp = newKeyEncryptor.encryptKeyData(encKey, rawKeyData, pos + 2, encLen);
                        iv = newKeyEncryptor.getCipherIV();

                    }
                    else
                    {
                        byte[] tmpIv = new byte[iv.length];

                        System.arraycopy(keyData, pos - iv.length, tmpIv, 0, tmpIv.length);
                        tmp = newKeyEncryptor.encryptKeyData(encKey, tmpIv, rawKeyData, pos + 2, encLen);
                    }

                    System.arraycopy(tmp, 0, keyData, pos + 2, tmp.length);
                    pos += 2 + encLen;
                }

                //
                // copy in checksum.
                //
                keyData[pos] = rawKeyData[pos];
                keyData[pos + 1] = rawKeyData[pos + 1];

                s2k = newKeyEncryptor.getS2K();
                newEncAlgorithm = newKeyEncryptor.getAlgorithm();
            }
            else
            {
                keyData = newKeyEncryptor.encryptKeyData(rawKeyData, 0, rawKeyData.length);

                iv = newKeyEncryptor.getCipherIV();

                s2k = newKeyEncryptor.getS2K();

                newEncAlgorithm = newKeyEncryptor.getAlgorithm();
            }
        }

        SecretKeyPacket             secret;
        if (key.secret instanceof SecretSubkeyPacket)
        {
            secret = new SecretSubkeyPacket(key.secret.getPublicKeyPacket(),
                newEncAlgorithm, s2kUsage, s2k, iv, keyData);
        }
        else
        {
            secret = new SecretKeyPacket(key.secret.getPublicKeyPacket(),
                newEncAlgorithm, s2kUsage, s2k, iv, keyData);
        }

        return new PGPSecretKey(secret, key.pub);
    }

    /**
     * Replace the passed the public key on the passed in secret key.
     *
     * @param secretKey secret key to change
     * @param publicKey new public key.
     * @return a new secret key.
     * @throws IllegalArgumentException if keyIDs do not match.
     */
    public static PGPSecretKey replacePublicKey(PGPSecretKey secretKey, PGPPublicKey publicKey)
    {
        if (publicKey.getKeyID() != secretKey.getKeyID())
        {
            throw new IllegalArgumentException("keyIDs do not match");
        }

        return new PGPSecretKey(secretKey.secret, publicKey);
    }

    /**
     * Create a new stripped secret key from a given public key, using the GNU DUMMY s2k type
     * with an empty secret body.
     *
     * @param publicKey a public key object
     * @return a stripped secret key object
     */
    public static PGPSecretKey constructGnuDummyKey(PGPPublicKey publicKey) {
        SecretKeyPacket secret;
        if (publicKey.isMasterKey()) {
            secret = new SecretKeyPacket(
                    publicKey.getPublicKeyPacket(),
                    // this is a dummy anyways, use CAST5 for compatibility (it's what gpg does)
                    SymmetricKeyAlgorithmTags.CAST5,
                    S2K.createDummyS2K(S2K.GNU_PROTECTION_MODE_NO_PRIVATE_KEY), null, null);
        } else {
            secret = new SecretSubkeyPacket(
                    publicKey.getPublicKeyPacket(),
                    // this is a dummy anyways, use CAST5 for compatibility (it's what gpg does)
                    SymmetricKeyAlgorithmTags.CAST5,
                    S2K.createDummyS2K(S2K.GNU_PROTECTION_MODE_NO_PRIVATE_KEY), null, null);
        }
        return new PGPSecretKey(secret, publicKey);
    }

    /**
     * Create a new stripped secret key from a given public key, using the GNU DUMMY
     * divert-to-card s2k type, giving a serial number as iv.
     *
     * @param publicKey a public key object
     * @param serial the serial number of the card, written as iv in the packet
     * @return a stripped secret key object
     */
    public static PGPSecretKey constructGnuDummyKey(PGPPublicKey publicKey, byte[] serial) {
        SecretKeyPacket secret;

        byte[] iv = new byte[16];
        System.arraycopy(serial, 0, iv, 0, serial.length > 16 ? 16 : serial.length);

        if (publicKey.isMasterKey()) {
            secret = new SecretKeyPacket(
                    publicKey.getPublicKeyPacket(),
                    SymmetricKeyAlgorithmTags.NULL,
                    SecretKeyPacket.USAGE_CHECKSUM,
                    S2K.createDummyS2K(S2K.GNU_PROTECTION_MODE_DIVERT_TO_CARD), iv, null);
        } else {
            secret = new SecretSubkeyPacket(
                    publicKey.getPublicKeyPacket(),
                    SymmetricKeyAlgorithmTags.NULL,
                    SecretKeyPacket.USAGE_CHECKSUM,
                    S2K.createDummyS2K(S2K.GNU_PROTECTION_MODE_DIVERT_TO_CARD), iv, null);
        }
        return new PGPSecretKey(secret, publicKey);
    }

    /**
     * Parse a secret key from one of the GPG S expression keys associating it with the passed in public key.
     *
     * @return a secret key object.
     */
    public static PGPSecretKey parseSecretKeyFromSExpr(InputStream inputStream, PBEProtectionRemoverFactory keyProtectionRemoverFactory, PGPPublicKey pubKey)
        throws IOException, PGPException
    {
        SXprUtils.skipOpenParenthesis(inputStream);

        String type;

        type = SXprUtils.readString(inputStream, inputStream.read());
        if (type.equals("protected-private-key"))
        {
            SXprUtils.skipOpenParenthesis(inputStream);

            String curveName;

            String keyType = SXprUtils.readString(inputStream, inputStream.read());
            if (keyType.equals("ecc"))
            {
                SXprUtils.skipOpenParenthesis(inputStream);

                String curveID = SXprUtils.readString(inputStream, inputStream.read());
                curveName = SXprUtils.readString(inputStream, inputStream.read());

                SXprUtils.skipCloseParenthesis(inputStream);
            }
            else
            {
                throw new PGPException("no curve details found");
            }

            byte[] qVal;

            SXprUtils.skipOpenParenthesis(inputStream);

            type = SXprUtils.readString(inputStream, inputStream.read());
            if (type.equals("q"))
            {
                qVal = SXprUtils.readBytes(inputStream, inputStream.read());
            }
            else
            {
                throw new PGPException("no q value found");
            }

            SXprUtils.skipCloseParenthesis(inputStream);

            byte[] dValue = getDValue(inputStream, keyProtectionRemoverFactory, curveName);
            // TODO: check SHA-1 hash.

            return new PGPSecretKey(new SecretKeyPacket(pubKey.getPublicKeyPacket(), SymmetricKeyAlgorithmTags.NULL, null, null, new ECSecretBCPGKey(new BigInteger(1, dValue)).getEncoded()), pubKey);
        }

        throw new PGPException("unknown key type found");
    }

    /**
        * Parse a secret key from one of the GPG S expression keys.
        *
        * @return a secret key object.
        */
       public static PGPSecretKey parseSecretKeyFromSExpr(InputStream inputStream, PBEProtectionRemoverFactory keyProtectionRemoverFactory, KeyFingerPrintCalculator fingerPrintCalculator)
           throws IOException, PGPException
       {
           SXprUtils.skipOpenParenthesis(inputStream);

           String type;

           type = SXprUtils.readString(inputStream, inputStream.read());
           if (type.equals("protected-private-key"))
           {
               SXprUtils.skipOpenParenthesis(inputStream);

               String curveName;

               String keyType = SXprUtils.readString(inputStream, inputStream.read());
               if (keyType.equals("ecc"))
               {
                   SXprUtils.skipOpenParenthesis(inputStream);

                   String curveID = SXprUtils.readString(inputStream, inputStream.read());
                   curveName = SXprUtils.readString(inputStream, inputStream.read());

                   if (curveName.startsWith("NIST "))
                   {
                       curveName = curveName.substring("NIST ".length());
                   }

                   SXprUtils.skipCloseParenthesis(inputStream);
               }
               else
               {
                   throw new PGPException("no curve details found");
               }

               byte[] qVal;

               SXprUtils.skipOpenParenthesis(inputStream);

               type = SXprUtils.readString(inputStream, inputStream.read());
               if (type.equals("q"))
               {
                   qVal = SXprUtils.readBytes(inputStream, inputStream.read());
               }
               else
               {
                   throw new PGPException("no q value found");
               }

               PublicKeyPacket pubPacket = new PublicKeyPacket(PublicKeyAlgorithmTags.ECDSA, new Date(), new ECDSAPublicBCPGKey(ECNamedCurveTable.getOID(curveName), new BigInteger(1, qVal)));

               SXprUtils.skipCloseParenthesis(inputStream);

               byte[] dValue = getDValue(inputStream, keyProtectionRemoverFactory, curveName);
               // TODO: check SHA-1 hash.

               return new PGPSecretKey(new SecretKeyPacket(pubPacket, SymmetricKeyAlgorithmTags.NULL, null, null, new ECSecretBCPGKey(new BigInteger(1, dValue)).getEncoded()), new PGPPublicKey(pubPacket, fingerPrintCalculator));
           }

           throw new PGPException("unknown key type found");
       }

    private static byte[] getDValue(InputStream inputStream, PBEProtectionRemoverFactory keyProtectionRemoverFactory, String curveName)
        throws IOException, PGPException
    {
        String type;
        SXprUtils.skipOpenParenthesis(inputStream);

        String protection;
        S2K s2k;
        byte[] iv;
        byte[] secKeyData;

        type = SXprUtils.readString(inputStream, inputStream.read());
        if (type.equals("protected"))
        {
            protection = SXprUtils.readString(inputStream, inputStream.read());

            SXprUtils.skipOpenParenthesis(inputStream);

            s2k = SXprUtils.parseS2K(inputStream);

            iv = SXprUtils.readBytes(inputStream, inputStream.read());

            SXprUtils.skipCloseParenthesis(inputStream);

            secKeyData = SXprUtils.readBytes(inputStream, inputStream.read());
        }
        else
        {
            throw new PGPException("protected block not found");
        }

        PBESecretKeyDecryptor keyDecryptor = keyProtectionRemoverFactory.createDecryptor(protection);

        // TODO: recognise other algorithms
        byte[] key = keyDecryptor.makeKeyFromPassPhrase(SymmetricKeyAlgorithmTags.AES_128, s2k);

        byte[] data = keyDecryptor.recoverKeyData(SymmetricKeyAlgorithmTags.AES_128, key, iv, secKeyData, 0, secKeyData.length);

        //
        // parse the secret key S-expr
        //
        InputStream keyIn = new ByteArrayInputStream(data);

        SXprUtils.skipOpenParenthesis(keyIn);
        SXprUtils.skipOpenParenthesis(keyIn);
        SXprUtils.skipOpenParenthesis(keyIn);
        String name = SXprUtils.readString(keyIn, keyIn.read());
        return SXprUtils.readBytes(keyIn, keyIn.read());
    }
}
