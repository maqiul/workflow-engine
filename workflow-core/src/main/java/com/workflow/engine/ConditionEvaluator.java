package com.workflow.engine;

import java.util.List;
import java.util.Map;

/**
 * 条件表达式求值器 - 自研迷你表达式引擎(零第三方依赖)
 *
 * 支持语法:
 * <pre>{@code
 *   days > 3 && manager == 'alice'
 *   amount >= 1000 || approved == true
 *   !(level < 2)
 *   type != 'leave'
 * }</pre>
 *
 * 文法(递归下降):
 * <pre>
 *   expr      := orExpr
 *   orExpr    := andExpr ( '||' andExpr )*
 *   andExpr   := notExpr ( '&&' notExpr )*
 *   notExpr   := '!' notExpr | cmpExpr
 *   cmpExpr   := addExpr ( ('=='|'!='|'>'|'>='|'<'|'<=') addExpr )?
 *   addExpr   := primary
 *   primary   := '(' expr ')' | NUMBER | STRING | BOOL | IDENT
 * </pre>
 *
 * 变量名解析规则:
 *  - 从 variables Map 取值;不存在时视为 null
 *  - null 与任何非 null 值比较(== / !=)结果为 true/false,与其他比较运算返回 false
 *  - 数字类型自动识别 int/long/double
 *  - 字符串字面量支持单引号 'xxx' 与双引号 "xxx"
 */
public final class ConditionEvaluator {

    private ConditionEvaluator() {}

    /** 表达式是否为空/空白(视为无条件 true) */
    public static boolean isEmpty(String expr) {
        return expr == null || expr.isBlank();
    }

    /**
     * 求值条件表达式
     * @param expr   表达式;null/空白 返回 true(无条件)
     * @param vars   流程变量
     * @return 表达式结果(非布尔返回其 truthy 判定)
     * @throws IllegalArgumentException 表达式语法错误
     */
    public static boolean eval(String expr, Map<String, Object> vars) {
        if (isEmpty(expr)) {
            return true;
        }
        // 兼容 JSP/Flowable 风格 ${...} 包裹：先剥壳再解析(裸表达式不受影响)
        String e = expr.trim();
        if (e.startsWith("${") && e.endsWith("}")) {
            e = e.substring(2, e.length() - 1).trim();
        }
        Tokenizer t = new Tokenizer(e);
        Object result = new Parser(t, vars).parseExpr();
        t.expectEnd();
        return truthy(result);
    }

    /** 布尔化:null→false, Boolean→本身, Number→!=0, String→!empty, 其他→true */
    private static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0d;
        if (v instanceof String s) return !s.isEmpty();
        return true;
    }

    // ==================== Tokenizer ====================

    private enum Kind { NUMBER, STRING, IDENT, OP, LPAREN, RPAREN, END }

    private record Token(Kind kind, String text, double num, int line) {
        @Override
        public String toString() {
            return kind == Kind.NUMBER ? String.valueOf(num) : text;
        }
    }

    private static final class Tokenizer {
        private final String src;
        private int pos;

        Tokenizer(String src) { this.src = src; }

        private Token next() {
            skipWs();
            if (pos >= src.length()) {
                return new Token(Kind.END, "", 0, pos);
            }
            char c = src.charAt(pos);
            int start = pos;
            // 数字
            if (Character.isDigit(c)) {
                while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) {
                    pos++;
                }
                double v = Double.parseDouble(src.substring(start, pos));
                return new Token(Kind.NUMBER, src.substring(start, pos), v, start);
            }
            // 字符串
            if (c == '\'' || c == '"') {
                char quote = c;
                pos++;
                StringBuilder sb = new StringBuilder();
                while (pos < src.length() && src.charAt(pos) != quote) {
                    if (src.charAt(pos) == '\\' && pos + 1 < src.length()) {
                        pos++;
                        char esc = src.charAt(pos);
                        switch (esc) {
                            case 'n' -> sb.append('\n');
                            case 't' -> sb.append('\t');
                            case '\\' -> sb.append('\\');
                            case '\'' -> sb.append('\'');
                            case '"' -> sb.append('"');
                            default -> sb.append(esc);
                        }
                    } else {
                        sb.append(src.charAt(pos));
                    }
                    pos++;
                }
                if (pos >= src.length()) {
                    throw new IllegalArgumentException("字符串未闭合: " + src.substring(start));
                }
                pos++; // 跳过闭合引号
                return new Token(Kind.STRING, sb.toString(), 0, start);
            }
            // 标识符 / 布尔
            if (Character.isLetter(c) || c == '_') {
                while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) {
                    pos++;
                }
                return new Token(Kind.IDENT, src.substring(start, pos), 0, start);
            }
            // 运算符
            String two = pos + 1 < src.length() ? src.substring(pos, pos + 2) : "";
            switch (two) {
                case "==", "!=", ">=", "<=", "&&", "||" -> {
                    pos += 2;
                    return new Token(Kind.OP, two, 0, start);
                }
                default -> {
                    if (c == '(') { pos++; return new Token(Kind.LPAREN, "(", 0, start); }
                    if (c == ')') { pos++; return new Token(Kind.RPAREN, ")", 0, start); }
                    if (c == '>' || c == '<' || c == '!') { pos++; return new Token(Kind.OP, String.valueOf(c), 0, start); }
                    throw new IllegalArgumentException("无法识别的字符 '" + c + "' @ " + start);
                }
            }
        }

        private void skipWs() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }

        /** 校验表达式已消费完(没有多余 token) */
        private void expectEnd() {
            Token t = next();
            if (t.kind() != Kind.END) {
                throw new IllegalArgumentException("表达式末尾有多余内容: " + t.text() + " @ " + t.line());
            }
        }
    }

    // ==================== Parser ====================

    private static final class Parser {
        private final Tokenizer t;
        private final Map<String, Object> vars;
        private Token cur;

        Parser(Tokenizer t, Map<String, Object> vars) {
            this.t = t;
            this.vars = vars;
            this.cur = t.next();
        }

        private void advance() { cur = t.next(); }

        private boolean isOp(String op) {
            return cur.kind() == Kind.OP && cur.text().equals(op);
        }

        Object parseExpr() { return parseOr(); }

        private Object parseOr() {
            Object left = parseAnd();
            while (isOp("||")) {
                advance();
                Object right = parseAnd();
                left = truthy(left) || truthy(right);
            }
            return left;
        }

        private Object parseAnd() {
            Object left = parseNot();
            while (isOp("&&")) {
                advance();
                Object right = parseNot();
                left = truthy(left) && truthy(right);
            }
            return left;
        }

        private Object parseNot() {
            if (isOp("!")) {
                advance();
                return !truthy(parseNot());
            }
            return parseCmp();
        }

        private Object parseCmp() {
            Object left = parsePrimary();
            if (cur.kind() == Kind.OP
                    && (cur.text().equals("==") || cur.text().equals("!=")
                        || cur.text().equals(">") || cur.text().equals(">=")
                        || cur.text().equals("<") || cur.text().equals("<="))) {
                String op = cur.text();
                advance();
                Object right = parsePrimary();
                return compare(op, left, right);
            }
            return left;
        }

        private Object parsePrimary() {
            switch (cur.kind()) {
                case NUMBER -> {
                    double v = cur.num();
                    advance();
                    return v;
                }
                case STRING -> {
                    String s = cur.text();
                    advance();
                    return s;
                }
                case LPAREN -> {
                    advance();
                    Object v = parseExpr();
                    if (cur.kind() != Kind.RPAREN) {
                        throw new IllegalArgumentException("缺少右括号 @ " + cur.line());
                    }
                    advance();
                    return v;
                }
                case IDENT -> {
                    String name = cur.text();
                    advance();
                    switch (name) {
                        case "true" -> { return true; }
                        case "false" -> { return false; }
                        case "null" -> { return null; }
                        default -> { return vars == null ? null : vars.get(name); }
                    }
                }
                default -> throw new IllegalArgumentException("意外的 token: " + cur + " @ " + cur.line());
            }
        }

        private static Object compare(String op, Object left, Object right) {
            if (left == null || right == null) {
                return switch (op) {
                    case "==" -> left == right;
                    case "!=" -> left != right;
                    default -> false;
                };
            }
            // 数值比较
            if (left instanceof Number ln && right instanceof Number rn) {
                double l = ln.doubleValue();
                double r = rn.doubleValue();
                return switch (op) {
                    case "==" -> l == r;
                    case "!=" -> l != r;
                    case ">" -> l > r;
                    case ">=" -> l >= r;
                    case "<" -> l < r;
                    case "<=" -> l <= r;
                    default -> throw new IllegalStateException("未知运算符 " + op);
                };
            }
            // 字符串/其他:只支持 == 和 !=
            return switch (op) {
                case "==" -> left.equals(right);
                case "!=" -> !left.equals(right);
                default -> throw new IllegalArgumentException(
                        "运算符 " + op + " 不支持非数值比较: " + left + " vs " + right);
            };
        }
    }
}
