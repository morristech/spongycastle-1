package org.spongycastle.openpgp.operator.jcajce;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.PublicKey;
import java.security.interfaces.DSAParams;
import java.security.interfaces.DSAPrivateKey;
import java.security.interfaces.DSAPublicKey;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.DSAPrivateKeySpec;
import java.security.spec.DSAPublicKeySpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Date;

import org.spongycastle.asn1.ASN1ObjectIdentifier;
import org.spongycastle.asn1.ASN1OctetString;
import org.spongycastle.asn1.DEROctetString;
import org.spongycastle.asn1.nist.NISTNamedCurves;
import org.spongycastle.asn1.x509.SubjectPublicKeyInfo;
import org.spongycastle.asn1.x9.ECNamedCurveTable;
import org.spongycastle.asn1.x9.X9ECParameters;
import org.spongycastle.asn1.x9.X9ECPoint;
import org.spongycastle.bcpg.BCPGKey;
import org.spongycastle.bcpg.DSAPublicBCPGKey;
import org.spongycastle.bcpg.DSASecretBCPGKey;
import org.spongycastle.bcpg.ECDHPublicBCPGKey;
import org.spongycastle.bcpg.ECDSAPublicBCPGKey;
import org.spongycastle.bcpg.EDDSAPublicBCPGKey;
import org.spongycastle.bcpg.ECSecretBCPGKey;
import org.spongycastle.bcpg.ElGamalPublicBCPGKey;
import org.spongycastle.bcpg.ElGamalSecretBCPGKey;
import org.spongycastle.bcpg.HashAlgorithmTags;
import org.spongycastle.bcpg.PublicKeyAlgorithmTags;
import org.spongycastle.bcpg.PublicKeyPacket;
import org.spongycastle.bcpg.RSAPublicBCPGKey;
import org.spongycastle.bcpg.RSASecretBCPGKey;
import org.spongycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.spongycastle.crypto.ec.CustomNamedCurves;
import org.spongycastle.jcajce.util.DefaultJcaJceHelper;
import org.spongycastle.jcajce.util.NamedJcaJceHelper;
import org.spongycastle.jcajce.util.ProviderJcaJceHelper;
import org.spongycastle.jce.interfaces.ElGamalPrivateKey;
import org.spongycastle.jce.interfaces.ElGamalPublicKey;
import org.spongycastle.jce.spec.ECNamedCurveSpec;
import org.spongycastle.jce.spec.ElGamalParameterSpec;
import org.spongycastle.jce.spec.ElGamalPrivateKeySpec;
import org.spongycastle.jce.spec.ElGamalPublicKeySpec;
import org.spongycastle.openpgp.PGPAlgorithmParameters;
import org.spongycastle.openpgp.PGPException;
import org.spongycastle.openpgp.PGPKdfParameters;
import org.spongycastle.openpgp.PGPPrivateKey;
import org.spongycastle.openpgp.PGPPublicKey;
import org.spongycastle.openpgp.operator.KeyFingerPrintCalculator;

public class JcaPGPKeyConverter
{
    private OperatorHelper helper = new OperatorHelper(new DefaultJcaJceHelper());
    private KeyFingerPrintCalculator fingerPrintCalculator = new JcaKeyFingerprintCalculator();

    public JcaPGPKeyConverter setProvider(Provider provider)
    {
        this.helper = new OperatorHelper(new ProviderJcaJceHelper(provider));

        return this;
    }

    public JcaPGPKeyConverter setProvider(String providerName)
    {
        this.helper = new OperatorHelper(new NamedJcaJceHelper(providerName));

        return this;
    }

    public PublicKey getPublicKey(PGPPublicKey publicKey)
        throws PGPException
    {
        KeyFactory fact;

        PublicKeyPacket publicPk = publicKey.getPublicKeyPacket();

        try
        {
            switch (publicPk.getAlgorithm())
            {
            case PublicKeyAlgorithmTags.RSA_ENCRYPT:
            case PublicKeyAlgorithmTags.RSA_GENERAL:
            case PublicKeyAlgorithmTags.RSA_SIGN:
                RSAPublicBCPGKey rsaK = (RSAPublicBCPGKey)publicPk.getKey();
                RSAPublicKeySpec rsaSpec = new RSAPublicKeySpec(rsaK.getModulus(), rsaK.getPublicExponent());

                fact = helper.createKeyFactory("RSA");

                return fact.generatePublic(rsaSpec);
            case PublicKeyAlgorithmTags.DSA:
                DSAPublicBCPGKey dsaK = (DSAPublicBCPGKey)publicPk.getKey();
                DSAPublicKeySpec dsaSpec = new DSAPublicKeySpec(dsaK.getY(), dsaK.getP(), dsaK.getQ(), dsaK.getG());

                fact = helper.createKeyFactory("DSA");

                return fact.generatePublic(dsaSpec);
            case PublicKeyAlgorithmTags.ELGAMAL_ENCRYPT:
            case PublicKeyAlgorithmTags.ELGAMAL_GENERAL:
                ElGamalPublicBCPGKey elK = (ElGamalPublicBCPGKey)publicPk.getKey();
                ElGamalPublicKeySpec elSpec = new ElGamalPublicKeySpec(elK.getY(), new ElGamalParameterSpec(elK.getP(), elK.getG()));

                fact = helper.createKeyFactory("ElGamal");

                return fact.generatePublic(elSpec);
            case PublicKeyAlgorithmTags.EC:
                ECDHPublicBCPGKey ecdhK = (ECDHPublicBCPGKey)publicPk.getKey();
                ECPublicKeySpec   ecDhSpec = new ECPublicKeySpec(
                    new java.security.spec.ECPoint(ecdhK.getPoint().getAffineXCoord().toBigInteger(), ecdhK.getPoint().getAffineYCoord().toBigInteger()),
                    getX9Parameters(ecdhK.getCurveOID()));
                fact = helper.createKeyFactory("ECDH");

                return fact.generatePublic(ecDhSpec);
            case PublicKeyAlgorithmTags.ECDSA:
                ECDSAPublicBCPGKey ecdsaK = (ECDSAPublicBCPGKey)publicPk.getKey();
                ECPublicKeySpec ecDsaSpec = new ECPublicKeySpec(
                    new java.security.spec.ECPoint(ecdsaK.getPoint().getAffineXCoord().toBigInteger(), ecdsaK.getPoint().getAffineYCoord().toBigInteger()),
                    getX9Parameters(ecdsaK.getCurveOID()));
                fact = helper.createKeyFactory("ECDSA");

                return fact.generatePublic(ecDsaSpec);
            case PublicKeyAlgorithmTags.EDDSA:
                EDDSAPublicBCPGKey eddsaK = (EDDSAPublicBCPGKey)publicPk.getKey();
                ECPublicKeySpec edDsaSpec = new ECPublicKeySpec(
                    new java.security.spec.ECPoint(eddsaK.getPoint().getX().toBigInteger(), eddsaK.getPoint().getY().toBigInteger()),
                    getX9Parameters(eddsaK.getCurveOID()));
                fact = helper.createKeyFactory("EDDSA");

                return fact.generatePublic(edDsaSpec);
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
            throw new PGPException("exception constructing public key", e);
        }
    }

    /**
     * Create a PGPPublicKey from the passed in JCA one.
     * <p/>
     * Note: the time passed in affects the value of the key's keyID, so you probably only want
     * to do this once for a JCA key, or make sure you keep track of the time you used.
     *
     * @param algorithm asymmetric algorithm type representing the public key.
     * @param algorithmParameters additional parameters to be stored against the public key.
     * @param pubKey    actual public key to associate.
     * @param time      date of creation.
     * @throws PGPException on key creation problem.
     */
    public PGPPublicKey getPGPPublicKey(int algorithm, PGPAlgorithmParameters algorithmParameters, PublicKey pubKey, Date time)
        throws PGPException
    {
        BCPGKey bcpgKey;

        if (pubKey instanceof RSAPublicKey)
        {
            RSAPublicKey rK = (RSAPublicKey)pubKey;

            bcpgKey = new RSAPublicBCPGKey(rK.getModulus(), rK.getPublicExponent());
        }
        else if (pubKey instanceof DSAPublicKey)
        {
            DSAPublicKey dK = (DSAPublicKey)pubKey;
            DSAParams dP = dK.getParams();

            bcpgKey = new DSAPublicBCPGKey(dP.getP(), dP.getQ(), dP.getG(), dK.getY());
        }
        else if (pubKey instanceof ElGamalPublicKey)
        {
            ElGamalPublicKey eK = (ElGamalPublicKey)pubKey;
            ElGamalParameterSpec eS = eK.getParameters();

            bcpgKey = new ElGamalPublicBCPGKey(eS.getP(), eS.getG(), eK.getY());
        }
        else if (pubKey instanceof ECPublicKey)
        {
            SubjectPublicKeyInfo keyInfo = SubjectPublicKeyInfo.getInstance(pubKey.getEncoded());

            // TODO: should probably match curve by comparison as well
            ASN1ObjectIdentifier  curveOid = ASN1ObjectIdentifier.getInstance(keyInfo.getAlgorithm().getParameters());

            X9ECParameters params = NISTNamedCurves.getByOID(curveOid);
            if (params == null)
            {
                params = CustomNamedCurves.getByOID(curveOid);
            }

            ASN1OctetString key = new DEROctetString(keyInfo.getPublicKeyData().getBytes());
            X9ECPoint derQ = new X9ECPoint(params.getCurve(), key);

            if (algorithm == PGPPublicKey.EC)
            {
                PGPKdfParameters kdfParams = (PGPKdfParameters)algorithmParameters;
                if (kdfParams == null)
                {
                    // We default to these as they are specified as mandatory in RFC 6631.
                    kdfParams = new PGPKdfParameters(HashAlgorithmTags.SHA256, SymmetricKeyAlgorithmTags.AES_128);
                }
                bcpgKey = new ECDHPublicBCPGKey(curveOid, derQ.getPoint(), kdfParams.getHashAlgorithm(), kdfParams.getSymmetricWrapAlgorithm());
            }
            else
            {
                if (algorithm == PGPPublicKey.EDDSA)
                {
                    bcpgKey = new EDDSAPublicBCPGKey(curveOid, derQ.getPoint());
                }
                else
                {
                    bcpgKey = new ECDSAPublicBCPGKey(curveOid, derQ.getPoint());
                }
            }
        }
        else
        {
            throw new PGPException("unknown key class");
        }

        return new PGPPublicKey(new PublicKeyPacket(algorithm, time, bcpgKey), fingerPrintCalculator);
    }

    /**
     * Create a PGPPublicKey from the passed in JCA one.
     * <p/>
     * Note: the time passed in affects the value of the key's keyID, so you probably only want
     * to do this once for a JCA key, or make sure you keep track of the time you used.
     *
     * @param algorithm asymmetric algorithm type representing the public key.
     * @param pubKey    actual public key to associate.
     * @param time      date of creation.
     * @throws PGPException on key creation problem.
     */
    public PGPPublicKey getPGPPublicKey(int algorithm, PublicKey pubKey, Date time)
        throws PGPException
    {
        return getPGPPublicKey(algorithm, null, pubKey, time);
    }

    public PrivateKey getPrivateKey(PGPPrivateKey privKey)
        throws PGPException
    {
        if (privKey instanceof JcaPGPPrivateKey)
        {
            return ((JcaPGPPrivateKey)privKey).getPrivateKey();
        }

        PublicKeyPacket pubPk = privKey.getPublicKeyPacket();
        BCPGKey privPk = privKey.getPrivateKeyDataPacket();

        try
        {
            KeyFactory fact;

            switch (pubPk.getAlgorithm())
            {
            case PGPPublicKey.RSA_ENCRYPT:
            case PGPPublicKey.RSA_GENERAL:
            case PGPPublicKey.RSA_SIGN:
                RSAPublicBCPGKey rsaPub = (RSAPublicBCPGKey)pubPk.getKey();
                RSASecretBCPGKey rsaPriv = (RSASecretBCPGKey)privPk;
                RSAPrivateCrtKeySpec rsaPrivSpec = new RSAPrivateCrtKeySpec(
                    rsaPriv.getModulus(),
                    rsaPub.getPublicExponent(),
                    rsaPriv.getPrivateExponent(),
                    rsaPriv.getPrimeP(),
                    rsaPriv.getPrimeQ(),
                    rsaPriv.getPrimeExponentP(),
                    rsaPriv.getPrimeExponentQ(),
                    rsaPriv.getCrtCoefficient());

                fact = helper.createKeyFactory("RSA");

                return fact.generatePrivate(rsaPrivSpec);
            case PGPPublicKey.DSA:
                DSAPublicBCPGKey dsaPub = (DSAPublicBCPGKey)pubPk.getKey();
                DSASecretBCPGKey dsaPriv = (DSASecretBCPGKey)privPk;
                DSAPrivateKeySpec dsaPrivSpec =
                    new DSAPrivateKeySpec(dsaPriv.getX(), dsaPub.getP(), dsaPub.getQ(), dsaPub.getG());

                fact = helper.createKeyFactory("DSA");

                return fact.generatePrivate(dsaPrivSpec);
            case PublicKeyAlgorithmTags.ECDH:
                ECDHPublicBCPGKey ecdhPub = (ECDHPublicBCPGKey)pubPk.getKey();
                ECSecretBCPGKey ecdhK = (ECSecretBCPGKey)privPk;
                ECPrivateKeySpec ecDhSpec = new ECPrivateKeySpec(
                                                    ecdhK.getX(),
                                                    getX9Parameters(ecdhPub.getCurveOID()));
                fact = helper.createKeyFactory("ECDH");

                return fact.generatePrivate(ecDhSpec);
            case PublicKeyAlgorithmTags.ECDSA:
                ECDSAPublicBCPGKey ecdsaPub = (ECDSAPublicBCPGKey)pubPk.getKey();
                ECSecretBCPGKey ecdsaK = (ECSecretBCPGKey)privPk;
                ECPrivateKeySpec ecDsaSpec = new ECPrivateKeySpec(
                                                    ecdsaK.getX(),
                                                    getX9Parameters(ecdsaPub.getCurveOID()));
                fact = helper.createKeyFactory("ECDSA");

                return fact.generatePrivate(ecDsaSpec);
            case PublicKeyAlgorithmTags.EDDSA:
                EDDSAPublicBCPGKey eddsaPub = (EDDSAPublicBCPGKey)pubPk.getKey();
                ECSecretBCPGKey eddsaK = (ECSecretBCPGKey)privPk;
                ECPrivateKeySpec edDsaSpec = new ECPrivateKeySpec(
                                                    eddsaK.getX(),
                                                    getX9Parameters(eddsaPub.getCurveOID()));
                fact = helper.createKeyFactory("EDDSA");

                return fact.generatePrivate(edDsaSpec);
            case PGPPublicKey.ELGAMAL_ENCRYPT:
            case PGPPublicKey.ELGAMAL_GENERAL:
                ElGamalPublicBCPGKey elPub = (ElGamalPublicBCPGKey)pubPk.getKey();
                ElGamalSecretBCPGKey elPriv = (ElGamalSecretBCPGKey)privPk;
                ElGamalPrivateKeySpec elSpec = new ElGamalPrivateKeySpec(elPriv.getX(), new ElGamalParameterSpec(elPub.getP(), elPub.getG()));

                fact = helper.createKeyFactory("ElGamal");

                return fact.generatePrivate(elSpec);
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

    /**
     * Convert a PrivateKey into a PGPPrivateKey.
     *
     * @param pub   the corresponding PGPPublicKey to privKey.
     * @param privKey  the private key for the key in pub.
     * @return a PGPPrivateKey
     * @throws PGPException
     */
    public PGPPrivateKey getPGPPrivateKey(PGPPublicKey pub, PrivateKey privKey)
        throws PGPException
    {
        BCPGKey privPk;

        switch (pub.getAlgorithm())
        {
        case PGPPublicKey.RSA_ENCRYPT:
        case PGPPublicKey.RSA_SIGN:
        case PGPPublicKey.RSA_GENERAL:
            RSAPrivateCrtKey rsK = (RSAPrivateCrtKey)privKey;

            privPk = new RSASecretBCPGKey(rsK.getPrivateExponent(), rsK.getPrimeP(), rsK.getPrimeQ());
            break;
        case PGPPublicKey.DSA:
            DSAPrivateKey dsK = (DSAPrivateKey)privKey;

            privPk = new DSASecretBCPGKey(dsK.getX());
            break;
        case PGPPublicKey.ELGAMAL_ENCRYPT:
        case PGPPublicKey.ELGAMAL_GENERAL:
            ElGamalPrivateKey esK = (ElGamalPrivateKey)privKey;

            privPk = new ElGamalSecretBCPGKey(esK.getX());
            break;
        case PGPPublicKey.EC:
        case PGPPublicKey.ECDSA:
        case PGPPublicKey.EDDSA:
            ECPrivateKey ecK = (ECPrivateKey)privKey;

            privPk = new ECSecretBCPGKey(ecK.getS());
            break;
        default:
            throw new PGPException("unknown key class");
        }

        return new PGPPrivateKey(pub.getKeyID(), pub.getPublicKeyPacket(), privPk);
    }

    private ECParameterSpec getX9Parameters(ASN1ObjectIdentifier curveOid)
    {
        X9ECParameters x9 = CustomNamedCurves.getByOID(curveOid);
        if (x9 == null)
        {
            x9 = ECNamedCurveTable.getByOID(curveOid);
        }

        return new ECNamedCurveSpec(curveOid.getId(), x9.getCurve(), x9.getG(), x9.getN(),
            x9.getH(), x9.getSeed());
    }
}
