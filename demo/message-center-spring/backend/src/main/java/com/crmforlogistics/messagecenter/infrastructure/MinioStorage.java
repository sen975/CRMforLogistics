package com.crmforlogistics.messagecenter.infrastructure;

import com.crmforlogistics.messagecenter.config.MinioConfig;
import io.minio.*;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.UUID;

@Service
public class MinioStorage {
    private final MinioClient client;
    private final String bucket;

    public MinioStorage(MinioClient client, MinioConfig.MinioProperties props) {
        this.client = client;
        this.bucket = props.bucket();
        ensureBucket();
    }

    private void ensureBucket() {
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        } catch (Exception e) {
            throw new RuntimeException("MinIO bucket init failed", e);
        }
    }

    public String store(byte[] data, String contentType) throws Exception {
        return store(UUID.randomUUID().toString(), data, contentType);
    }

    public String store(String objectKey, byte[] data, String contentType) throws Exception {
        return store(objectKey, new ByteArrayInputStream(data), data.length, contentType);
    }

    public String store(String objectKey, InputStream input, long size, String contentType) throws Exception {
        if (size < 0) throw new IllegalArgumentException("Object size cannot be negative");
        client.putObject(PutObjectArgs.builder()
                .bucket(bucket).object(objectKey)
                .stream(input, size, -1)
                .contentType(contentType == null || contentType.isBlank()
                        ? "application/octet-stream" : contentType).build());
        return objectKey;
    }

    public InputStream get(String objectKey) throws Exception {
        return client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
    }

    public InputStream getRange(String objectKey, long offset, long length) throws Exception {
        if (offset < 0 || length <= 0) {
            throw new IllegalArgumentException("Object range must be positive");
        }
        return client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey)
                .offset(offset).length(length).build());
    }

    public void remove(String objectKey) throws Exception {
        client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
    }

    public String bucketName() {
        return bucket;
    }
}
