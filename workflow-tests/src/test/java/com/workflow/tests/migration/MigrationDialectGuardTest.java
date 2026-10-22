package com.workflow.tests.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迁移脚本的三库方言守卫。
 *
 * <p>本项目已在真 MySQL / PostgreSQL 上被同类问题咬过两次：
 * <ul>
 *   <li><b>V8</b>：{@code ADD COLUMN IF NOT EXISTS} —— MariaDB 扩展，MySQL 抛语法错误
 *       → 两个 MySQL 跨库测试全挂，PG 全过；</li>
 *   <li><b>V15</b>：{@code payload CLOB} —— MySQL 5.0+ 已移除 CLOB，只剩 TEXT/LONGTEXT
 *       → 同样 MySQL 全挂、PG 全过（PG 支持 CLOB）。</li>
 * </ul>
 *
 * <p>两次症状一模一样、都在 nightly 才暴露，代价不小。故用本测试把禁令固化下来。
 *
 * <p><b>只检查非注释行</b>：脚本注释里会引用这些关键词来解释「为什么不能用」，
 * 一并拦下会让修复本身无法落笔。
 */
@DisplayName("迁移脚本三库方言守卫")
class MigrationDialectGuardTest {

    /** 迁移脚本目录（相对本模块工作目录；也兼容从仓库根运行） */
    private static final List<String> CANDIDATE_DIRS = List.of(
            "../workflow-persistence-flyway/src/main/resources/db/migration",
            "workflow-persistence-flyway/src/main/resources/db/migration",
            "../workflow-persistence-flyway/src/main/resources/db/migration/"
    );

    /**
     * 禁用模式 → 原因说明。
     * <p>每条都要注明「为什么三库不通用」与「正确写法是什么」。
     */
    private static final List<Rule> FORBIDDEN = List.of(
            new Rule("ADD COLUMN IF NOT EXISTS",
                    "MariaDB 扩展语法，MySQL 不支持（照 V9 写法直接 ADD COLUMN 即可；"
                            + "Flyway 保证每版本只执行一次，本就不需要 IF NOT EXISTS 兜重入）"),
            new Rule("COMMENT ON COLUMN",
                    "PostgreSQL 专属语法，MySQL 无此语法（列注释请写成 SQL 行注释 -- ）"),
            new Rule("CLOB",
                    "MySQL 5.0+ 已移除 CLOB 类型，只剩 TEXT/LONGTEXT（大字段一律用 TEXT）"),
            new Rule("ADD COLUMN IF NOT EXISTS ",
                    "MariaDB 扩展语法，MySQL 不支持"),
            new Rule("CREATE INDEX IF NOT EXISTS",
                    "MySQL 不支持 IF NOT EXISTS 加在 CREATE INDEX 上"),
            new Rule("SERIAL",
                    "PostgreSQL 专有类型，MySQL 不支持（用 BIGINT + 自行递增）")
    );

    private record Rule(String keyword, String why) {
        /** 构造匹配该关键词、且不在注释里的正则 */
        Pattern pattern() {
            // 前置行首/非注释标记，确保不是注释行
            return Pattern.compile("^\\s*(?!(--|#|/\\*|\\*))[^\\n]*"
                    + Pattern.quote(keyword), Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
        }
    }

    @Test
    @DisplayName("迁移脚本不得使用单库专属语法（MySQL/PG/H2 通用性守卫）")
    void migrations_usePortableDialectOnly() throws IOException {
        Path dir = locateMigrationDir();
        assertThat(dir).as("应能找到迁移脚本目录").isNotNull();

        List<Path> scripts;
        try (Stream<Path> files = Files.list(dir)) {
            scripts = files.filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .sorted()
                    .toList();
        }
        assertThat(scripts).as("迁移脚本不应为空").isNotEmpty();

        List<String> violations = new ArrayList<>();
        for (Path script : scripts) {
            List<String> lines = Files.readAllLines(script, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (isCommentLine(line)) {
                    continue;
                }
                for (Rule rule : FORBIDDEN) {
                    if (rule.pattern().matcher(line).find()) {
                        violations.add(script.getFileName() + " 第 " + (i + 1) + " 行："
                                + "含单库专属语法 [" + rule.keyword() + "] —— " + rule.why()
                                + "　实际内容: " + line.trim());
                    }
                }
            }
        }

        assertThat(violations)
                .as("迁移脚本出现单库专属语法（历史上 V8 的 ADD COLUMN IF NOT EXISTS、"
                        + "V15 的 CLOB 都在真 MySQL 上炸过，PG 却全过）")
                .isEmpty();
    }

    /** 是否注释行（--、#、/* 与块注释内部） */
    private static boolean isCommentLine(String line) {
        String t = line.trim();
        return t.isEmpty()
                || t.startsWith("--")
                || t.startsWith("#")
                || t.startsWith("/*")
                || t.startsWith("*");
    }

    private static Path locateMigrationDir() {
        for (String candidate : CANDIDATE_DIRS) {
            Path p = Paths.get(candidate);
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        return null;
    }
}
