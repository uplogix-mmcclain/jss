/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.jss.pkcs12;

import java.io.CharConversionException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import org.mozilla.jss.CryptoManager;
import org.mozilla.jss.NotInitializedException;
import org.mozilla.jss.asn1.ASN1Template;
import org.mozilla.jss.asn1.ASN1Value;
import org.mozilla.jss.asn1.INTEGER;
import org.mozilla.jss.asn1.InvalidBERException;
import org.mozilla.jss.asn1.OCTET_STRING;
import org.mozilla.jss.asn1.SEQUENCE;
import org.mozilla.jss.asn1.Tag;
import org.mozilla.jss.crypto.CryptoToken;
import org.mozilla.jss.crypto.DigestAlgorithm;
import org.mozilla.jss.crypto.HMACAlgorithm;
import org.mozilla.jss.crypto.JSSMessageDigest;
import org.mozilla.jss.crypto.JSSSecureRandom;
import org.mozilla.jss.crypto.KeyGenAlgorithm;
import org.mozilla.jss.crypto.KeyGenerator;
import org.mozilla.jss.crypto.PBEKeyGenParams;
import org.mozilla.jss.crypto.SymmetricKey;
import org.mozilla.jss.crypto.TokenException;
import org.mozilla.jss.pkcs7.DigestInfo;
import org.mozilla.jss.pkix.primitive.AlgorithmIdentifier;
import org.mozilla.jss.util.Password;

public class MacData implements ASN1Value {

    private DigestInfo mac;
    private OCTET_STRING macSalt;
    private INTEGER macIterationCount;

    private static final int DEFAULT_ITERATIONS = 1;

    // 20 is the length of SHA-1 hash output
    private static final int SALT_LENGTH = 20;

    public DigestInfo getMac() {
        return mac;
    }

    public OCTET_STRING getMacSalt() {
        return macSalt;
    }

    public INTEGER getMacIterationCount() {
        return macIterationCount;
    }

    public MacData() { }

    /**
     * Creates a MacData from the given parameters.
     *
     * @param macIterationCount 1 is the default and should be used for
     *      maximum compatibility. null can also be used, in which case
     *      the macIterationCount will be omitted from the structure
     *      (and the default value of 1 will be implied).
     */
    public MacData(DigestInfo mac, OCTET_STRING macSalt,
                INTEGER macIterationCount)
    {
        if( mac==null || macSalt==null || macIterationCount==null ) {
             throw new IllegalArgumentException("null parameter");
        }

        this.mac = mac;
        this.macSalt = macSalt;
        this.macIterationCount = macIterationCount;
    }

    /**
     * Creates a MacData by computing a HMAC on the given bytes. An HMAC
     * is a message authentication code, which is a keyed digest. It proves
     * not only that data has not been tampered with, but also that the
     * entity that created the HMAC possessed the symmetric key.
     *
     * @param password The password used to generate a key using a PBE mechanism.
     * @param macSalt The salt used as input to the PBE key generation mechanism.
     *      If null is passed in, new random salt will be created.
     * @param iterations The iteration count for creating the PBE key.
     * @param toBeMACed The data on which the HMAC will be computed.
     * @exception NotInitializedException If the crypto subsystem
     *      has not been initialized yet.
     * @exception TokenException If an error occurs on a crypto token.
     */
    public MacData( Password password, byte[] macSalt,
                    int iterations, byte[] toBeMACed )
        throws NotInitializedException,
            DigestException, TokenException, CharConversionException
    {
        this(password, macSalt, iterations, toBeMACed, null);
    }


    /**
     * Creates a MacData by computing a HMAC on the given bytes. An HMAC
     * is a message authentication code, which is a keyed digest. It proves
     * not only that data has not been tampered with, but also that the
     * entity that created the HMAC possessed the symmetric key.
     *
     * @param password The password used to generate a key using a PBE mechanism.
     * @param macSalt The salt used as input to the PBE key generation mechanism.
     *      If null is passed in, new random salt will be created.
     * @param iterations The iteration count for creating the PBE key.
     * @param toBeMACed The data on which the HMAC will be computed.
     * @param algID The algorithm used to compute the HMAC, If null the
     *      SHA1 will be used.
     * @exception NotInitializedException If the crypto subsystem
     *      has not been initialized yet.
     * @exception TokenException If an error occurs on a crypto token.
     */
    public MacData( Password password, byte[] macSalt,
                    int iterations, byte[] toBeMACed,
                    AlgorithmIdentifier algID)
        throws NotInitializedException,
            DigestException, TokenException, CharConversionException
    {
        CryptoManager cm = CryptoManager.getInstance();
        CryptoToken token = cm.getInternalCryptoToken();

        if (macSalt == null) {
            JSSSecureRandom rand = cm.createPseudoRandomNumberGenerator();
            macSalt = new byte[SALT_LENGTH];
            rand.nextBytes(macSalt);
        }

        PBEKeyGenParams params = new PBEKeyGenParams(password, macSalt, iterations);

        try {
            // generate key from password and salt
            if(algID == null) {
                algID = new AlgorithmIdentifier(DigestAlgorithm.SHA1.toOID());
            }
            KeyGenerator kg = null;
            JSSMessageDigest digest = null;
            if(DigestAlgorithm.SHA1.toOID().equals(algID.getOID())){
                kg = token.getKeyGenerator(KeyGenAlgorithm.PBA_SHA1_HMAC);
                digest = token.getDigestContext(HMACAlgorithm.SHA1);
            }
            if(DigestAlgorithm.SHA256.toOID().equals(algID.getOID())){
                kg = token.getKeyGenerator(KeyGenAlgorithm.PBE_SHA256_HMAC);
                digest = token.getDigestContext(HMACAlgorithm.SHA256);
            }
            if(DigestAlgorithm.SHA384.toOID().equals(algID.getOID())){
                kg = token.getKeyGenerator(KeyGenAlgorithm.PBE_SHA384_HMAC);
                digest = token.getDigestContext(HMACAlgorithm.SHA384);
            }
            if(DigestAlgorithm.SHA512.toOID().equals(algID.getOID())){
                kg = token.getKeyGenerator(KeyGenAlgorithm.PBE_SHA512_HMAC);
                digest = token.getDigestContext(HMACAlgorithm.SHA512);
            }
            if(kg == null || digest == null) {
                throw new NoSuchAlgorithmException("Algorithm (oid:" + algID.getOID().toDottedString() + ") not managed for digest");
            }
            kg.setCharToByteConverter(new PasswordConverter());
            kg.initialize(params);
            SymmetricKey key = kg.generate();

            // perform the digesting
            digest.initHMAC(key);
            byte[] digestBytes = digest.digest(toBeMACed);

            // put everything into a DigestInfo
            this.mac = new DigestInfo(algID, new OCTET_STRING(digestBytes));
            this.macSalt = new OCTET_STRING(macSalt);
            this.macIterationCount = new INTEGER(iterations);

        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("HMAC algorithm not found on internal " +
                    "token: " + e.getMessage(), e);

        } catch (InvalidAlgorithmParameterException e) {
            throw new RuntimeException("Invalid PBE algorithm parameters: " + e.getMessage(), e);

        } catch (java.lang.IllegalStateException e) {
            throw new RuntimeException("Illegal state: " + e.getMessage(), e);

        } catch (InvalidKeyException e) {
            throw new RuntimeException("Invalid key: " + e.getMessage(), e);

        } finally {
            params.clear();
        }
    }

    ///////////////////////////////////////////////////////////////////////
    // DER encoding
    ///////////////////////////////////////////////////////////////////////

    @Override
    public Tag getTag() {
        return TAG;
    }
    private static final Tag TAG = SEQUENCE.TAG;


    @Override
    public void encode(OutputStream ostream) throws IOException {
        encode(TAG, ostream);
    }


    @Override
    public void encode(Tag implicitTag, OutputStream ostream)
        throws IOException
    {
        SEQUENCE seq = new SEQUENCE();

        seq.addElement(mac);
        seq.addElement(macSalt);
        if( ! macIterationCount.equals(new INTEGER(DEFAULT_ITERATIONS)) ) {
            // 1 is the default, only include this element if it is not
            // the default
            seq.addElement(macIterationCount);
        }

        seq.encode(implicitTag, ostream);
    }

    private static final Template templateInstance = new Template();
    public static final Template getTemplate() {
        return templateInstance;
    }

    /**
     * A Template for decoding a MacData from its BER encoding.
     */
    public static class Template implements ASN1Template {

        private SEQUENCE.Template seqt;

        public Template() {
            seqt = new SEQUENCE.Template();

            seqt.addElement( DigestInfo.getTemplate() );
            seqt.addElement( OCTET_STRING.getTemplate() );
            seqt.addElement( INTEGER.getTemplate(),
                                new INTEGER(DEFAULT_ITERATIONS) );
        }


        @Override
        public boolean tagMatch(Tag tag) {
            return TAG.equals(tag);
        }


        @Override
        public ASN1Value decode(InputStream istream)
            throws InvalidBERException, IOException
        {
            return decode(TAG, istream);
        }


        @Override
        public ASN1Value decode(Tag implicitTag, InputStream istream)
            throws InvalidBERException, IOException
        {
            SEQUENCE seq = (SEQUENCE) seqt.decode(implicitTag, istream);

            return new MacData( (DigestInfo) seq.elementAt(0),
                                (OCTET_STRING) seq.elementAt(1),
                                (INTEGER) seq.elementAt(2) );
        }
    }
}
