/*
 * Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 */
package com.emc.object.s3.jersey;

import com.emc.object.EncryptionConfig;
import com.emc.object.s3.S3Config;
import com.emc.object.s3.S3ObjectMetadata;
import com.emc.object.s3.bean.*;
import com.emc.object.s3.request.*;
import com.emc.object.util.RestUtil;
import com.emc.vipr.transform.TransformException;
import com.emc.vipr.transform.encryption.DoesNotNeedRekeyException;
import com.emc.vipr.transform.encryption.EncryptionTransformFactory;
import com.sun.jersey.api.client.ClientHandler;
import com.sun.jersey.api.client.filter.ClientFilter;

import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Implements client-side encryption on top of the S3 API.
 * Encryption method uses "Envelope Encryption". With envelope
 * encryption, a master asymmetric (RSA) key is used to encrypt and decrypt a per-object
 * symmetric (AES) key.  This means that every object is encrypted using a unique key
 * so breaking any one object's key does not compromise the encryption on other objects.
 * Key rotation can also be accomplished by creating a new asymmetric key and
 * re-encrypting the object keys without re-encrypting the actual object content.
 * <br>
 * To use encryption, you will first need to create a keystore, set a password, and
 * then create an RSA key to use as your master encryption key.  This can be accomplished
 * using the 'keytool' application that comes with Java In this example, we create a
 * 2048-bit RSA key and call it "masterkey".  If the keystore does not already exist, it
 * will be created an you will be prompted for a keystore password.
 * <br>
 * <pre>
 * $ keytool -genkeypair -keystore keystore.jks -alias masterkey -keyalg RSA \
 *   -keysize 2048 -dname "CN=My Name, OU=My Division, O=My Company, L=My Location, ST=MA, C=US"
 * Enter keystore password: changeit
 * Re-enter new password: changeit
 * Enter key password for <masterkey>
 *   (RETURN if same as keystore password):
 * </pre>
 * Inside your application, you can then construct and load a Keystore object,
 * {@link java.security.KeyStore#load(InputStream, char[])}  Once the keystore has been loaded, you then
 * construct a EncryptionConfig object with the keystore:
 * <br>
 * <pre>
 * EncryptionConfig ec = new EncryptionConfig(keystore,
 *             keystorePassword.toCharArray(), "masterkey", provider, 128);
 * </pre>
 * The "provider" argument is used to specify the security provider to be used for
 * cryptographic operations.  You can set it to null to use the default provider(s) as
 * specified in your jre/lib/security/java.security file.  The final argument is the AES
 * encryption key size.  Note that most JDKs only support 128-bit AES encryption by
 * default and required the "unlimited strength jurisdiction policy files" to be
 * installed to achieve 256-bit support.  See your JRE/JDK download page for details.
 * <br>
 * Once you have your EncryptionConfig, simply pass this to the constructor of
 * {@link com.emc.object.s3.jersey.S3EncryptionClient}:
 * <br>
 * <pre>
 * S3Client s3Client = new S3EncryptionClient(s3Config, ec);
 * </pre>
 * <br>
 * After you have your S3EncryptionClient constructed, you may use it like any other
 * S3Client instance with the following limitations:
 * <ul>
 * <li>Byte range (partial) reads are not supported
 * <li>Byte range (partial) updates including appends are not supported.
 * <li>Pre-signed URLs are not supported because there is no way to
 * decompress and/or decrypt the content for the receiver.
 * </ul>
 */
public class S3EncryptionClient extends S3JerseyClient {
    private static final String UNSUPPORTED_MSG = "This operation is not supported by "
            + "the encryption client";
    private static final String PARTIAL_UPDATE_MSG = "Partial object updates and/or "
            + "appends are not supported by the encryption client";
    private static final String PARTIAL_READ_MSG = "Partial object reads are not "
            + "supported by the encryption client";

    private EncryptionTransformFactory factory;

    public S3EncryptionClient(S3Config s3Config, EncryptionConfig encryptionConfig) {
        super(s3Config);
        this.factory = encryptionConfig.getFactory();

        // insert codec filter into chain before the checksum filter
        // as usual, Jersey makes this quite hard

        // first, make a list of the filters
        List<ClientFilter> filters = new ArrayList<ClientFilter>();
        ClientHandler handler = client.getHeadHandler();
        while (handler instanceof ClientFilter) {
            ClientFilter filter = (ClientFilter) handler;
            if (filter instanceof ChecksumFilter) {
                // insert codec filter before checksum filter
                filters.add(new CodecFilter(factory));
            }
            filters.add(filter);
            handler = filter.getNext();
        }

        // then re-create the filter list (must reverse the list because filters are inserted back to front)
        Collections.reverse(filters);
        client.removeAllFilters();
        for (ClientFilter filter : filters) {
            client.addFilter(filter);
        }
    }

    /**
     * "Rekeys" an object.  This operation re-encrypts the object's key with the most
     * current master key and is used to implement key rotation.  Note that when you
     * create a new master key, your EncryptionConfig should keep all of the old master
     * key(s) until you have rekeyed all of the objects so you can decrypt the old
     * objects.
     *
     * @param bucketName the name of the bucket that holds the object to rekey.
     * @param key        the name of the object to rekey.
     * @return true if the object was successfully rekeyed, false if the object already uses the new key
     * @throws java.lang.IllegalArgumentException if the object is not encrypted
     */
    public boolean rekey(String bucketName, String key) throws DoesNotNeedRekeyException {

        // read the metadata for the object
        S3ObjectMetadata objectMetadata = getObjectMetadata(bucketName, key);
        Map<String, String> userMetadata = objectMetadata.getUserMetadata();

        // get the encryption spec used
        String encMode = EncryptionConfig.getEncryptionMode(userMetadata);
        if (encMode == null) {
            throw new IllegalArgumentException("Object is not encrypted");
        }

        if (factory.canDecode(encMode, userMetadata)) {
            try {
                // re-sign the object encryption key
                Map<String, String> rekeyMeta = factory.rekey(userMetadata);
                for (String name : rekeyMeta.keySet()) {
                    objectMetadata.userMetadata(name, rekeyMeta.get(name));
                }

                // push the re-signed keys as metadata
                // TODO: revert after testing
                AccessControlList acl = getObjectAcl(bucketName, key);
                super.copyObject(new CopyObjectRequest(bucketName, key, bucketName, key + ".temp"));
                super.copyObject(new CopyObjectRequest(bucketName, key + ".temp", bucketName, key)
                        .withAcl(acl).withObjectMetadata(objectMetadata));
                super.deleteObject(bucketName, key + ".temp");

                return true;
            } catch (DoesNotNeedRekeyException e) {
                return false;
            } catch (TransformException e) {
                throw new RuntimeException("Error rekeying object: " + bucketName + "/" + key, e);
            }
        } else {
            throw new RuntimeException("Cannot handle encryption mode '" + encMode + "'");
        }
    }

    @Override
    public PutObjectResult putObject(PutObjectRequest request) {
        if (request.getRange() != null)
            throw new UnsupportedOperationException(PARTIAL_UPDATE_MSG);

        // make user metadata available as a request property
        if (request.getObjectMetadata() == null) request.setObjectMetadata(new S3ObjectMetadata());
        Map<String, String> userMeta = request.getObjectMetadata().getUserMetadata();
        request.property(RestUtil.PROPERTY_USER_METADATA, userMeta);

        // activate codec filter
        request.property(RestUtil.PROPERTY_ENCODE_ENTITY, Boolean.TRUE);

        // TODO: remove after testing
        @SuppressWarnings("unchecked")
        PutObjectRequest tempRequest = new PutObjectRequest(request);
        tempRequest.setKey(request.getKey() + ".temp");

        // write data
        super.putObject(tempRequest);

        // encryption filter will modify userMeta with encryption metadata *after* the object is transferred
        // we must send a separate metadata update or the object will be unreadable
        // TODO: should this be atomic?  how do we handle rollback?
        CopyObjectRequest metadataUpdate = new CopyObjectRequest(request.getBucketName(), tempRequest.getKey(),
                request.getBucketName(), request.getKey()).withAcl(request.getAcl()).withObjectMetadata(request.getObjectMetadata());
        // TODO: revert after testing
        PutObjectResult result = super.copyObject(metadataUpdate);
        super.deleteObject(request.getBucketName(), tempRequest.getKey());
        return result;
    }

    @Override
    public <T> GetObjectResult<T> getObject(GetObjectRequest request, Class<T> objectType) {
        if (request.getRange() != null)
            throw new UnsupportedOperationException(PARTIAL_READ_MSG);

        // activate codec filter
        request.property(RestUtil.PROPERTY_DECODE_ENTITY, Boolean.TRUE);

        return super.getObject(request, objectType);
    }

    @Override
    public CopyObjectResult copyObject(CopyObjectRequest request) {
        // TODO: we should support adding metadata somehow; maybe be intelligent about required metadata
        if (request.getObjectMetadata() != null)
            throw new UnsupportedOperationException(UNSUPPORTED_MSG + " (copy and replace metadata)");
        return super.copyObject(request);
    }

    @Override
    public URL getPresignedUrl(PresignedUrlRequest request) {
        throw new UnsupportedOperationException(UNSUPPORTED_MSG);
    }

    @Override
    public void setObjectMetadata(String bucketName, String key, S3ObjectMetadata objectMetadata) {
        throw new UnsupportedOperationException(UNSUPPORTED_MSG);
    }

    @Override
    public InitiateMultipartUploadResult initiateMultipartUpload(InitiateMultipartUploadRequest request) {
        throw new UnsupportedOperationException(UNSUPPORTED_MSG);
    }

    @Override
    public MultipartPartETag uploadPart(UploadPartRequest request) {
        throw new UnsupportedOperationException(UNSUPPORTED_MSG);
    }

    @Override
    public CopyPartResult copyPart(CopyPartRequest request) {
        throw new UnsupportedOperationException(UNSUPPORTED_MSG);
    }

    @Override
    public CompleteMultipartUploadResult completeMultipartUpload(CompleteMultipartUploadRequest request) {
        throw new UnsupportedOperationException(UNSUPPORTED_MSG);
    }

    @Override
    public void abortMultipartUpload(AbortMultipartUploadRequest request) {
        throw new UnsupportedOperationException(UNSUPPORTED_MSG);
    }
}