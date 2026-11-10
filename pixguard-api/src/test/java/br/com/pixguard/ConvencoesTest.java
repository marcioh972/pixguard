package br.com.pixguard;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Version;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

/**
 * A lista PROIBIDO do projeto, verificada automaticamente a cada build. Sem Spring context e sem banco:
 * lê o código-fonte (sem comentários e sem textos literais) e usa reflexão sobre as classes compiladas.
 */
@DisplayName("Convenções do projeto (a lista PROIBIDO, verificada automaticamente)")
class ConvencoesTest {

    private static final String PACOTE_BASE = "br.com.pixguard";
    private static final Path MODULO = raizDoModulo();
    private static final Path FONTES = MODULO.resolve("src/main/java");
    private static final Path TESTES = MODULO.resolve("src/test/java");
    private static final Path MIGRACOES = MODULO.resolve("src/main/resources/db/migration");

    // ---------- infraestrutura ----------

    /** Funciona tanto com o diretório de trabalho no módulo (Maven, IntelliJ) quanto na raiz do repositório. */
    private static Path raizDoModulo() {
        if (Files.exists(Path.of("src/main/java"))) {
            return Path.of(".");
        }
        return Path.of("pixguard-api");
    }

    private static List<Path> arquivosJava(Path raiz) throws IOException {
        try (Stream<Path> arquivos = Files.walk(raiz)) {
            return arquivos.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static String ler(Path arquivo) throws IOException {
        return Files.readString(arquivo, StandardCharsets.UTF_8);
    }

    private static String nome(Path arquivo) {
        return arquivo.toString().replace('\\', '/');
    }

    /**
     * Devolve só o CÓDIGO: remove comentários e troca textos literais por "". As regras valem para o que o
     * programa faz, não para o que está escrito em comentários ou mensagens.
     */
    static String somenteCodigo(String fonte) {
        StringBuilder saida = new StringBuilder(fonte.length());
        int i = 0;
        int n = fonte.length();
        while (i < n) {
            char c = fonte.charAt(i);
            if (fonte.startsWith("//", i)) {
                while (i < n && fonte.charAt(i) != '\n') {
                    i++;
                }
            } else if (fonte.startsWith("/*", i)) {
                int fim = fonte.indexOf("*/", i + 2);
                i = fim < 0 ? n : fim + 2;
                saida.append(' ');
            } else if (fonte.startsWith("\"\"\"", i)) {
                int fim = fonte.indexOf("\"\"\"", i + 3);
                i = fim < 0 ? n : fim + 3;
                saida.append("\"\"");
            } else if (c == '"') {
                i++;
                while (i < n && fonte.charAt(i) != '"') {
                    i += fonte.charAt(i) == '\\' ? 2 : 1;
                }
                i++;
                saida.append("\"\"");
            } else if (c == '\'') {
                i++;
                while (i < n && fonte.charAt(i) != '\'') {
                    i += fonte.charAt(i) == '\\' ? 2 : 1;
                }
                i++;
                saida.append("' '");
            } else {
                saida.append(c);
                i++;
            }
        }
        return saida.toString();
    }

    /** Arquivos do código principal que casam com a regex (já sem comentários e textos). */
    private static List<String> violacoes(Pattern regra) throws IOException {
        List<String> encontrados = new ArrayList<>();
        for (Path arquivo : arquivosJava(FONTES)) {
            Matcher m = regra.matcher(somenteCodigo(ler(arquivo)));
            while (m.find()) {
                encontrados.add(nome(arquivo) + " -> " + m.group().trim());
            }
        }
        return encontrados;
    }

    private static Set<Class<?>> classesCom(Class<? extends java.lang.annotation.Annotation> anotacao) throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(anotacao));
        Set<Class<?>> classes = new HashSet<>();
        for (BeanDefinition definicao : scanner.findCandidateComponents(PACOTE_BASE)) {
            classes.add(Class.forName(definicao.getBeanClassName()));
        }
        return classes;
    }

    // ---------- tempo, dinheiro, exceções ----------

    @Test
    @DisplayName("nenhum Instant.now() (nem LocalDate.now() etc.) no código: o tempo vem do Clock injetado")
    void semTempoDireto() throws IOException {
        Pattern tempoDireto = Pattern.compile(
                "\\b(Instant|LocalDate|LocalDateTime|LocalTime|OffsetDateTime|ZonedDateTime)\\s*\\.\\s*now\\s*\\("
                        + "|System\\s*\\.\\s*currentTimeMillis|new\\s+Date\\s*\\(");

        assertThat(violacoes(tempoDireto)).isEmpty();
    }

    @Test
    @DisplayName("o relógio real é criado em um único lugar (ClockConfig)")
    void relogioRealSoNoClockConfig() throws IOException {
        List<String> fora = violacoes(Pattern.compile("Clock\\s*\\.\\s*(systemUTC|systemDefaultZone|system)\\s*\\("))
                .stream().filter(v -> !v.contains("ClockConfig.java")).toList();

        assertThat(fora).isEmpty();
    }

    @Test
    @DisplayName("nunca double/float (nem Double/Float) no código: dinheiro é BigDecimal")
    void semDoubleOuFloat() throws IOException {
        assertThat(violacoes(Pattern.compile("\\b(double|float|Double|Float)\\b"))).isEmpty();
    }

    @Test
    @DisplayName("nenhum catch vazio")
    void semCatchVazio() throws IOException {
        assertThat(violacoes(Pattern.compile("catch\\s*\\([^)]*\\)\\s*\\{\\s*\\}"))).isEmpty();
    }

    @Test
    @DisplayName("logs não mencionam o motivo da devolução (texto livre do cliente, pode conter dado pessoal)")
    void logsSemMotivo() throws IOException {
        assertThat(violacoes(Pattern.compile("\\blog\\s*\\.\\s*\\w+\\s*\\([^;]*\\b(motivo|getMotivo)\\b"))).isEmpty();
    }

    // ---------- dependências proibidas ----------

    @Test
    @DisplayName("sem Lombok e sem H2: nem nos poms nem nos imports")
    void semLombokNemH2() throws IOException {
        List<String> encontrados = new ArrayList<>();
        for (String pom : List.of("pom.xml", "../pom.xml", "../mock-bank/pom.xml")) {
            Path arquivo = MODULO.resolve(pom);
            if (Files.exists(arquivo)) {
                String texto = ler(arquivo).toLowerCase();
                if (texto.contains("lombok") || texto.contains("com.h2database") || texto.contains("<artifactid>h2</artifactid>")) {
                    encontrados.add(nome(arquivo));
                }
            }
        }
        for (Path raiz : List.of(FONTES, TESTES)) {
            for (Path arquivo : arquivosJava(raiz)) {
                String codigo = somenteCodigo(ler(arquivo));
                if (Pattern.compile("import\\s+(lombok|org\\.h2)\\.").matcher(codigo).find()) {
                    encontrados.add(nome(arquivo));
                }
            }
        }

        assertThat(encontrados).isEmpty();
    }

    // ---------- camadas ----------

    @Test
    @DisplayName("@Transactional só existe na camada service, e a fachada WebhookService não é transacional")
    void transactionalSoNaCamadaDeService() throws IOException {
        List<String> fora = new ArrayList<>();
        for (Path arquivo : arquivosJava(FONTES)) {
            boolean usa = somenteCodigo(ler(arquivo)).contains("@Transactional");
            boolean ehService = nome(arquivo).contains("/service/");
            boolean ehFachadaDoWebhook = nome(arquivo).endsWith("/WebhookService.java");
            if (usa && (!ehService || ehFachadaDoWebhook)) {
                fora.add(nome(arquivo));
            }
        }

        assertThat(fora).isEmpty();
    }

    @Test
    @DisplayName("controllers existem, nunca são transacionais")
    void controllersNaoSaoTransacionais() throws ClassNotFoundException {
        Set<Class<?>> controllers = classesCom(RestController.class);

        assertThat(controllers).as("controllers encontrados").hasSizeGreaterThanOrEqualTo(3);
        for (Class<?> controller : controllers) {
            assertThat(AnnotationUtils.findAnnotation(controller, Transactional.class))
                    .as("classe %s", controller.getSimpleName()).isNull();
            for (Method metodo : controller.getDeclaredMethods()) {
                assertThat(AnnotationUtils.findAnnotation(metodo, Transactional.class))
                        .as("método %s.%s", controller.getSimpleName(), metodo.getName()).isNull();
            }
        }
    }

    @Test
    @DisplayName("a API nunca expõe entidade: nem como retorno nem como parâmetro dos controllers")
    void apiNaoExpoeEntidades() throws ClassNotFoundException {
        List<String> expostas = new ArrayList<>();
        for (Class<?> controller : classesCom(RestController.class)) {
            for (Method metodo : controller.getDeclaredMethods()) {
                if (!Modifier.isPublic(metodo.getModifiers())) {
                    continue;
                }
                Set<Class<?>> tipos = new HashSet<>();
                coletar(metodo.getGenericReturnType(), tipos);
                for (Type parametro : metodo.getGenericParameterTypes()) {
                    coletar(parametro, tipos);
                }
                for (Class<?> tipo : tipos) {
                    if (tipo.isAnnotationPresent(Entity.class)) {
                        expostas.add(controller.getSimpleName() + "." + metodo.getName() + " -> " + tipo.getSimpleName());
                    }
                }
            }
        }

        assertThat(expostas).isEmpty();
    }

    private static void coletar(Type tipo, Set<Class<?>> acumulado) {
        if (tipo instanceof Class<?> classe) {
            acumulado.add(classe);
            if (classe.isArray()) {
                coletar(classe.getComponentType(), acumulado);
            }
        } else if (tipo instanceof ParameterizedType parametrizado) {
            coletar(parametrizado.getRawType(), acumulado);
            for (Type argumento : parametrizado.getActualTypeArguments()) {
                coletar(argumento, acumulado);
            }
        } else if (tipo instanceof GenericArrayType arranjo) {
            coletar(arranjo.getGenericComponentType(), acumulado);
        } else if (tipo instanceof WildcardType curinga) {
            for (Type limite : curinga.getUpperBounds()) {
                coletar(limite, acumulado);
            }
        }
    }

    // ---------- entidades ----------

    @Test
    @DisplayName("entidades: sem setter público, construtor protegido, @Version, enums STRING e equals/hashCode")
    void entidadesSeguemAsConvencoes() throws Exception {
        Set<Class<?>> entidades = classesCom(Entity.class);
        assertThat(entidades).as("entidades encontradas").hasSizeGreaterThanOrEqualTo(3);

        List<String> problemas = new ArrayList<>();
        for (Class<?> entidade : entidades) {
            String nome = entidade.getSimpleName();

            for (Method metodo : entidade.getDeclaredMethods()) {
                if (Modifier.isPublic(metodo.getModifiers()) && metodo.getName().startsWith("set")) {
                    problemas.add(nome + ": setter público " + metodo.getName());
                }
            }
            Constructor<?> semArgumentos = entidade.getDeclaredConstructor();
            if (!Modifier.isProtected(semArgumentos.getModifiers())) {
                problemas.add(nome + ": construtor sem argumentos deve ser protected");
            }
            boolean temVersion = false;
            for (Field campo : entidade.getDeclaredFields()) {
                temVersion |= campo.isAnnotationPresent(Version.class);
                if (campo.getType().isEnum()) {
                    Enumerated enumerado = campo.getAnnotation(Enumerated.class);
                    if (enumerado == null || enumerado.value() != EnumType.STRING) {
                        problemas.add(nome + "." + campo.getName() + ": enum deve ser @Enumerated(STRING)");
                    }
                }
            }
            if (!temVersion) {
                problemas.add(nome + ": falta @Version");
            }
            try {
                entidade.getDeclaredMethod("equals", Object.class);
                entidade.getDeclaredMethod("hashCode");
            } catch (NoSuchMethodException e) {
                problemas.add(nome + ": equals/hashCode devem usar a chave de negócio");
            }
        }

        assertThat(problemas).isEmpty();
    }

    // ---------- migrações ----------

    @Test
    @DisplayName("migrações numeradas em sequência, sem buracos, e todo índice tem comentário justificando a consulta")
    void migracoesSequenciaisEIndicesComentados() throws IOException {
        List<Path> arquivos;
        try (Stream<Path> stream = Files.list(MIGRACOES)) {
            arquivos = stream.filter(p -> p.getFileName().toString().matches("V\\d+__.+\\.sql")).toList();
        }
        List<Integer> versoes = arquivos.stream()
                .map(p -> Integer.parseInt(p.getFileName().toString().replaceAll("^V(\\d+)__.*$", "$1")))
                .sorted().toList();
        assertThat(versoes).as("versões").isNotEmpty().containsExactlyElementsOf(
                java.util.stream.IntStream.rangeClosed(1, versoes.size()).boxed().toList());

        Pattern criaIndice = Pattern.compile("^\\s*CREATE\\s+(UNIQUE\\s+)?INDEX\\b.*", Pattern.CASE_INSENSITIVE);
        List<String> semComentario = new ArrayList<>();
        for (Path arquivo : arquivos) {
            List<String> linhas = Files.readAllLines(arquivo, StandardCharsets.UTF_8);
            for (int i = 0; i < linhas.size(); i++) {
                if (criaIndice.matcher(linhas.get(i)).matches()
                        && (i == 0 || !linhas.get(i - 1).trim().startsWith("--"))) {
                    semComentario.add(arquivo.getFileName() + ":" + (i + 1) + " " + linhas.get(i).trim());
                }
            }
        }

        assertThat(semComentario).isEmpty();
    }
}
