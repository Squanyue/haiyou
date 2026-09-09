package com.haiyou.shuzhi.exhibition.service.impl;

import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * 仅覆盖展示文件名的 MultipartFile 包装
 *
 * @author exhibition
 * @date 2026-08-07
 */
class RenamedMultipartFile implements MultipartFile {

    private final MultipartFile delegate;
    private final String filename;

    RenamedMultipartFile(MultipartFile delegate, String filename) {
        this.delegate = delegate;
        this.filename = filename;
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public String getOriginalFilename() {
        return filename;
    }

    @Override
    public String getContentType() {
        return delegate.getContentType();
    }

    @Override
    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    @Override
    public long getSize() {
        return delegate.getSize();
    }

    @Override
    public byte[] getBytes() throws IOException {
        return delegate.getBytes();
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return delegate.getInputStream();
    }

    @Override
    public void transferTo(File dest) throws IOException, IllegalStateException {
        delegate.transferTo(dest);
    }
}
