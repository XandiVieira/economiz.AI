package com.relyon.economizai.service.extraction;

import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class CsvSeedLoader {

    private CsvSeedLoader() {}

    static List<String[]> load(String classpath) throws IOException {
        var rows = new ArrayList<String[]>();
        try (var reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(classpath).getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                var trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                rows.add(line.split(",", -1));
            }
        }
        return rows;
    }
}
