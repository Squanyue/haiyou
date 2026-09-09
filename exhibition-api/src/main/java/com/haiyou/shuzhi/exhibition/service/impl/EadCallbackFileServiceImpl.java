package com.haiyou.shuzhi.exhibition.service.impl;

import com.haiyou.shuzhi.exhibition.config.EadProperties;
import com.haiyou.shuzhi.exhibition.service.EadCallbackFileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * EAD 回调附件落盘实现
 * <p>
 * 同一参数下多个附件会打包成一个 zip 保存到本地。
 *
 * @author exhibition
 * @date 2026-08-07
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EadCallbackFileServiceImpl implements EadCallbackFileService {

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HHmmss");

    private final EadProperties eadProperties;

    @Override
    public List<Map<String, Object>> saveCallbackFiles(Map<String, List<MultipartFile>> filesByField) {
        List<Map<String, Object>> saved = new ArrayList<Map<String, Object>>();
        if (filesByField == null || filesByField.isEmpty()) {
            return saved;
        }

        Path rootDir = resolveRootDir();
        try {
            Files.createDirectories(rootDir);
        } catch (IOException ex) {
            throw new IllegalStateException("创建回调附件目录失败: " + rootDir + ", " + ex.getMessage(), ex);
        }

        LocalDateTime now = LocalDateTime.now();
        Path dayDir = rootDir.resolve(DAY_FORMAT.format(now));
        try {
            Files.createDirectories(dayDir);
        } catch (IOException ex) {
            throw new IllegalStateException("创建回调附件日期目录失败: " + dayDir + ", " + ex.getMessage(), ex);
        }

        for (Map.Entry<String, List<MultipartFile>> entry : filesByField.entrySet()) {
            String fieldName = entry.getKey();
            List<MultipartFile> files = filterNonEmpty(entry.getValue());
            if (files.isEmpty()) {
                continue;
            }
            if (files.size() == 1) {
                saved.add(saveOne(dayDir, fieldName, files.get(0), now));
            } else {
                saved.add(saveAsZip(dayDir, fieldName, files, now));
            }
        }

        log.info("EAD 回调附件已保存 {} 个到 {}", saved.size(), dayDir.toAbsolutePath());
        return saved;
    }

    private List<MultipartFile> filterNonEmpty(List<MultipartFile> files) {
        List<MultipartFile> result = new ArrayList<MultipartFile>();
        if (files == null) {
            return result;
        }
        for (MultipartFile file : files) {
            if (file != null && !file.isEmpty()) {
                result.add(file);
            }
        }
        return result;
    }

    private Map<String, Object> saveOne(Path dayDir, String fieldName, MultipartFile file, LocalDateTime now) {
        String originalFilename = file.getOriginalFilename();
        String safeName = sanitizeFileName(originalFilename);
        String storedName = TIME_FORMAT.format(now) + "_"
                + shortUuid()
                + "_" + safeName;
        Path target = dayDir.resolve(storedName).normalize();
        ensureUnderDir(dayDir, target, originalFilename);

        try {
            file.transferTo(target.toFile());
        } catch (IOException ex) {
            throw new IllegalStateException("保存回调附件失败: " + originalFilename + ", " + ex.getMessage(), ex);
        }

        Map<String, Object> info = baseInfo(fieldName, originalFilename, storedName, target, file.getSize(), file.getContentType());
        info.put("packed", Boolean.FALSE);
        info.put("sourceCount", 1);
        log.info("EAD 回调附件已落盘, field={}, original={}, path={}",
                fieldName, originalFilename, target.toAbsolutePath());
        return info;
    }

    private Map<String, Object> saveAsZip(Path dayDir, String fieldName, List<MultipartFile> files, LocalDateTime now) {
        String zipName = TIME_FORMAT.format(now) + "_"
                + shortUuid()
                + "_" + sanitizeFileName(fieldName) + "_attachments.zip";
        Path target = dayDir.resolve(zipName).normalize();
        ensureUnderDir(dayDir, target, zipName);

        List<String> sourceNames = new ArrayList<String>();
        try (OutputStream out = Files.newOutputStream(target);
             ZipOutputStream zipOut = new ZipOutputStream(out)) {
            for (MultipartFile file : files) {
                String entryName = uniqueZipEntryName(sourceNames, sanitizeFileName(file.getOriginalFilename()));
                sourceNames.add(entryName);
                zipOut.putNextEntry(new ZipEntry(entryName));
                zipOut.write(file.getBytes());
                zipOut.closeEntry();
            }
        } catch (IOException ex) {
            throw new IllegalStateException("打包回调附件失败: field=" + fieldName + ", " + ex.getMessage(), ex);
        }

        long size;
        try {
            size = Files.size(target);
        } catch (IOException ex) {
            size = 0L;
        }

        Map<String, Object> info = baseInfo(fieldName, zipName, zipName, target, size, "application/zip");
        info.put("packed", Boolean.TRUE);
        info.put("sourceCount", files.size());
        info.put("sourceFilenames", sourceNames);
        log.info("EAD 回调多附件已打包落盘, field={}, sourceCount={}, path={}",
                fieldName, files.size(), target.toAbsolutePath());
        return info;
    }

    private String uniqueZipEntryName(List<String> existing, String preferred) {
        String name = StringUtils.hasText(preferred) ? preferred : "unnamed";
        if (!existing.contains(name)) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        int index = 2;
        String candidate;
        do {
            candidate = base + "_" + index + ext;
            index++;
        } while (existing.contains(candidate));
        return candidate;
    }

    private Map<String, Object> baseInfo(String fieldName, String originalFilename, String storedFilename,
                                         Path target, long size, String contentType) {
        Map<String, Object> info = new LinkedHashMap<String, Object>();
        info.put("fieldName", fieldName);
        info.put("originalFilename", originalFilename);
        info.put("storedFilename", storedFilename);
        info.put("savedPath", target.toAbsolutePath().toString());
        info.put("size", size);
        info.put("contentType", contentType);
        return info;
    }

    private void ensureUnderDir(Path dayDir, Path target, String name) {
        if (!target.startsWith(dayDir.normalize())) {
            throw new IllegalStateException("非法附件文件名: " + name);
        }
    }

    private Path resolveRootDir() {
        String dir = eadProperties.getCallbackFileDir();
        if (!StringUtils.hasText(dir)) {
            dir = "D:/haiyou/shuzhi/code/file";
        }
        return Paths.get(dir).toAbsolutePath().normalize();
    }

    private String sanitizeFileName(String originalFilename) {
        String name = StringUtils.hasText(originalFilename) ? originalFilename : "unnamed";
        name = name.replace("\\", "_").replace("/", "_").replace("..", "_");
        name = name.replaceAll("[\\r\\n\\t]", "_");
        if (!StringUtils.hasText(name)) {
            return "unnamed";
        }
        return name;
    }

    private String shortUuid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
