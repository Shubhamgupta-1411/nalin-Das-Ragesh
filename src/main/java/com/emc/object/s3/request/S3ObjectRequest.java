/*
 * Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 */
package com.emc.object.s3.request;

import com.emc.object.Method;

public class S3ObjectRequest extends AbstractBucketRequest {
    private String key;

    public S3ObjectRequest(Method method, String bucketName, String key, String subresource) {
        super(method, bucketName, key, subresource);
        this.key = key;
    }

    public S3ObjectRequest(S3ObjectRequest other) {
        super(other);
        this.key = other.key;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
        setPath(key);
    }
}