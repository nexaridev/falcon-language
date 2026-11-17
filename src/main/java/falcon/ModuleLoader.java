package falcon;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static falcon.Ast.*;

/** Loads local files once and links calls without merging their public namespaces. */
final class ModuleLoader {
    private record Module(Program program, String namespace, Map<String, Module> imports) {}
    private final Map<Path, Module> modules = new LinkedHashMap<>();
    private final Map<String, Module> standardModules = new LinkedHashMap<>();
    private final Set<Path> active = new HashSet<>();
    private final Set<String> activeStandard = new HashSet<>();
    private final Map<String, String> sources = new LinkedHashMap<>();
    private Path overlayPath;
    private String overlaySource;

    Program load(Path entry, String source) throws IOException {
        overlayPath = entry.toRealPath();
        overlaySource = source;
        return load(entry);
    }

    Program load(Path entry) throws IOException {
        Path path = entry.toRealPath();
        visit(path, true, null);
        var functions = new ArrayList<Function>();
        var structs = new ArrayList<StructDecl>();
        var loaded = new ArrayList<>(modules.values());
        loaded.addAll(standardModules.values());
        for (Module module : loaded) {
            for (Function function : module.program().functions()) {
                for (Parameter parameter : function.parameters()) checkBinding(parameter.name(), module);
                functions.add(new Function(function.name(), qualify(function.parameters(), module), function.result(),
                        qualify(function.resultStructName(), module), (Block) link(function.body(), module), module.namespace()));
            }
            for (StructDecl struct : module.program().structs()) {
                Token name = struct.name();
                Token qualified = new Token(name.kind(), module.namespace() + name.text(), name.line(), name.column(), name.file());
                structs.add(new StructDecl(qualified, struct.fields().stream()
                        .map(field -> new StructField(field.name(), field.type(), qualify(field.structName(), module))).toList()));
            }
        }
        return new Program(List.copyOf(functions), List.of(), List.copyOf(structs));
    }

    private Module visit(Path path, boolean entry, Token importSite) throws IOException {
        if (active.contains(path)) throw new FalconError(importSite, "Circular module import involving '" + path.getFileName() + "'.");
        Module cached = modules.get(path);
        if (cached != null) return cached;
        if (active.size() >= 128) throw new FalconError(importSite, "Module import depth exceeds 128 files.");
        String source;
        try { source = path.equals(overlayPath) ? overlaySource : Files.readString(path); }
        catch (IOException e) {
            if (importSite == null) throw e;
            throw new FalconError(importSite, "Cannot read module '" + path + "': " + e.getMessage());
        }
        sources.put(path.toString(), source);
        Program program = new Parser(new Lexer(source, path.toString()).scan()).parse();
        Module module = new Module(program, entry ? "" : "@" + modules.size() + "::", new LinkedHashMap<>());
        modules.put(path, module); active.add(path);
        Set<String> localNames = new HashSet<>();
        for (Function function : program.functions()) localNames.add(function.name().text());
        for (Import declaration : program.imports()) {
            String alias = declaration.alias().text();
            if (alias.equals("say") || localNames.contains(alias) || module.imports().containsKey(alias))
                throw new FalconError(declaration.alias(), "Module alias '" + alias + "' is already in use.");
            String standard = standardName(declaration.path(), "std/");
            if (standard != null) {
                module.imports().put(alias, standardModule(standard, declaration.token()));
                continue;
            }
            String installed = installedName(declaration.path());
            if (installed != null) {
                Path modules = findModules(path.getParent());
                if (modules == null) throw new FalconError(declaration.token(), "No modules folder found in this project. Run 'falcon install owner/repository' first.");
                modules = modules.resolve(installed).normalize();
                if (!modules.startsWith(path.getParent().resolve("modules").normalize())) throw new FalconError(declaration.token(), "Invalid installed module path.");
                Path main = modules.resolve("module.json");
                if (!Files.isRegularFile(main)) throw new FalconError(declaration.token(), "Installed module '" + installed + "' has no module.json manifest.");
                try {
                    Object manifest = Json.read(Files.readString(main));
                    if (!(manifest instanceof Map<?, ?> data) || !(data.get("main") instanceof String entryFile) || !entryFile.endsWith(".fc") || Path.of(entryFile).isAbsolute())
                        throw new FalconError(declaration.token(), "Invalid module.json: expected a relative .fc 'main' path.");
                    Path resolved = modules.resolve(entryFile).normalize();
                    if (!resolved.startsWith(modules)) throw new FalconError(declaration.token(), "Module main path escapes its installation folder.");
                    module.imports().put(alias, visit(resolved, false, declaration.token()));
                } catch (IOException | InvalidPathException e) { throw new FalconError(declaration.token(), "Cannot read installed module manifest: " + e.getMessage()); }
                continue;
            }
            String nativeName = standardName(declaration.path(), "native/");
            if (nativeName != null) {
                module.imports().put(alias, nativeModule(nativeName, declaration.token()));
                continue;
            }
            String name = declaration.path().substring(0, Math.max(0, declaration.path().length() - 3));
            if (declaration.named() && !Files.exists(path.getParent().resolve(declaration.path())) && hasStandard(name)) {
                module.imports().put(alias, standardModule(name, declaration.token()));
                continue;
            }
            Path imported;
            try {
                Path relative = Path.of(declaration.path());
                if (declaration.path().isBlank() || relative.isAbsolute() || relative.getRoot() != null || !declaration.path().endsWith(".fc"))
                    throw new FalconError(declaration.token(), "Use a relative path to a .fc module.");
                imported = path.getParent().resolve(relative).toRealPath();
            } catch (IOException | InvalidPathException e) {
                throw new FalconError(declaration.token(), "Cannot load module '" + declaration.path() + "': " + e.getMessage());
            }
            module.imports().put(alias, visit(imported, false, declaration.token()));
        }
        active.remove(path);
        return module;
    }

    private String installedName(String importPath) {
        String name = importPath;
        if (name.startsWith("modules/")) name = name.substring(8);
        if (!name.matches("[A-Za-z0-9_.-]+") || name.equals(".") || name.equals("..")) return null;
        Path local = overlayPath == null ? null : findModules(overlayPath.getParent());
        if (local != null) local = local.resolve(name).resolve("module.json");
        return local != null && Files.isRegularFile(local) ? name : null;
    }

    private Path findModules(Path directory) {
        for (Path current = directory; current != null; current = current.getParent()) {
            Path candidate = current.resolve("modules");
            if (Files.isDirectory(candidate)) return candidate;
        }
        return null;
    }

    private String standardName(String path, String prefix) {
        if (!path.startsWith(prefix)) return null;
        String name = path.substring(prefix.length());
        return name.matches("[\\p{L}_][\\p{L}\\p{N}_]*\\.fc") ? name.substring(0, name.length() - 3) : null;
    }

    private boolean hasStandard(String name) {
        if (!name.matches("[\\p{L}_][\\p{L}\\p{N}_]*")) return false;
        Path projectStandard = findStandardDirectory(overlayPath == null ? Path.of("").toAbsolutePath() : overlayPath.getParent());
        if (projectStandard != null && Files.isRegularFile(projectStandard.resolve(name + ".fc"))) return true;
        String directory = System.getProperty("falcon.stdlib", "");
        return directory.isBlank() ? ModuleLoader.class.getResource("/stdlib/" + name + ".fc") != null
                : Files.isRegularFile(Path.of(directory).resolve(name + ".fc"));
    }

    private Module nativeModule(String name, Token site) {
        Program program = StandardLibrary.module(name);
        if (program.functions().isEmpty()) throw new FalconError(site, "Unknown runtime module '" + name + "'.");
        return new Module(program, "native::" + name + "::", Map.of());
    }

    private Module standardModule(String name, Token site) throws IOException {
        if (activeStandard.contains(name)) throw new FalconError(site, "Circular standard-library import involving '" + name + "'.");
        Module cached = standardModules.get(name);
        if (cached != null) return cached;
        if (activeStandard.size() >= 128) throw new FalconError(site, "Standard-library import depth exceeds 128 files.");
        activeStandard.add(name);
        String file = "stdlib/" + name + ".fc";
        String source;
        Path projectStandard = findStandardDirectory(overlayPath == null ? Path.of("").toAbsolutePath() : overlayPath.getParent());
        String directory = projectStandard != null && Files.isRegularFile(projectStandard.resolve(name + ".fc"))
                ? projectStandard.toString() : System.getProperty("falcon.stdlib", "");
        try {
            if (!directory.isBlank()) {
                Path path = Path.of(directory).resolve(name + ".fc").toRealPath();
                file = path.toString();
                source = path.equals(overlayPath) ? overlaySource : Files.readString(path);
            } else {
                try (var stream = ModuleLoader.class.getResourceAsStream("/stdlib/" + name + ".fc")) {
                    if (stream == null) throw new IOException("Bundled stdlib/" + name + ".fc is missing; rebuild Falcon.");
                    source = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        } catch (IOException | InvalidPathException e) {
            throw new FalconError(site, "Cannot load Falcon standard library: " + e.getMessage());
        }
        sources.put(file, source);
        Program program = new Parser(new Lexer(source, file).scan()).parse();
        var imports = new LinkedHashMap<String, Module>();
        Set<String> names = new HashSet<>();
        for (Function function : program.functions()) names.add(function.name().text());
        for (Import declaration : program.imports()) {
            String alias = declaration.alias().text();
            if (alias.equals("say") || names.contains(alias) || imports.containsKey(alias))
                throw new FalconError(declaration.alias(), "Module alias '" + alias + "' is already in use.");
            String nativeName = standardName(declaration.path(), "native/");
            String standard = standardName(declaration.path(), "std/");
            if (nativeName != null) imports.put(alias, nativeModule(nativeName, declaration.token()));
            else {
                if (standard == null) standard = standardName("std/" + declaration.path(), "std/");
                if (standard == null) throw new FalconError(declaration.token(), "Standard libraries import sibling .fc files or std/native modules.");
                imports.put(alias, standardModule(standard, declaration.token()));
            }
        }
        Module module = new Module(program, "std::" + name + "::", imports);
        standardModules.put(name, module);
        activeStandard.remove(name);
        return module;
    }

    private Path findStandardDirectory(Path directory) {
        for (Path current = directory; current != null; current = current.getParent()) {
            Path candidate = current.resolve("stdlib");
            if (Files.isDirectory(candidate)) return candidate;
        }
        return null;
    }

    private Stmt link(Stmt stmt, Module module) {
        if (stmt instanceof Block b) return new Block(b.token(), b.statements().stream().map(s -> link(s, module)).toList());
        if (stmt instanceof Binding b) {
            checkBinding(b.name(), module);
            return new Binding(b.token(), b.name(), b.annotation(), qualify(b.annotationStructName(), module),
                    link(b.value(), module), b.mutable());
        }
        if (stmt instanceof Assign a) return new Assign(a.token(), link(a.value(), module));
        if (stmt instanceof IndexAssign a) return new IndexAssign(a.token(), link(a.target(), module), link(a.index(), module), link(a.value(), module));
        if (stmt instanceof FieldAssign a) return new FieldAssign(a.token(), link(a.target(), module), a.field(), link(a.value(), module));
        if (stmt instanceof ExprStmt e) return new ExprStmt(e.token(), link(e.value(), module));
        if (stmt instanceof When w) return new When(w.token(), link(w.condition(), module), (Block) link(w.yes(), module), w.no() == null ? null : link(w.no(), module));
        if (stmt instanceof While w) return new While(w.token(), link(w.condition(), module), (Block) link(w.body(), module));
        if (stmt instanceof Each e) {
            checkBinding(e.name(), module);
            return new Each(e.token(), e.name(), link(e.iterable(), module), (Block) link(e.body(), module));
        }
        if (stmt instanceof Give g) return new Give(g.token(), g.value() == null ? null : link(g.value(), module));
        if (stmt instanceof TryCatch t) return new TryCatch(t.token(), (Block) link(t.body(), module),
                t.errorName(), (Block) link(t.handler(), module));
        throw new IllegalStateException("Unknown statement");
    }

    private Expr link(Expr expr, Module module) {
        if (expr instanceof ListExpr list) return new ListExpr(list.token(), list.elements().stream().map(e -> link(e, module)).toList());
        if (expr instanceof Index index) return new Index(index.token(), link(index.target(), module), link(index.index(), module));
        if (expr instanceof Field field) return new Field(field.token(), link(field.target(), module), field.name());
        if (expr instanceof Call call) {
            String name = call.token().text();
            String target = name;
            int dot = name.indexOf('.');
            if (dot >= 0) {
                String alias = name.substring(0, dot), member = name.substring(dot + 1);
                Module imported = module.imports().get(alias);
                if (imported == null) throw new FalconError(call.token(), "Unknown module '" + alias + "'; add a 'use' declaration.");
                boolean hasFunction = imported.program().functions().stream().anyMatch(f -> f.name().text().equals(member));
                boolean hasStruct = imported.program().structs().stream().anyMatch(s -> s.name().text().equals(member));
                if (!hasFunction && !hasStruct)
                    throw new FalconError(call.token(), "Module '" + alias + "' has no function or struct '" + member + "'.");
                target = imported.namespace() + member;
            } else if (!name.equals("say")) target = module.namespace() + name;
            return new Call(call.token(), call.arguments().stream().map(a -> link(a, module)).toList(), target);
        }
        if (expr instanceof Unary u) return new Unary(u.token(), link(u.value(), module));
        if (expr instanceof Binary b) return new Binary(b.token(), link(b.left(), module), link(b.right(), module));
        return expr;
    }

    private void checkBinding(Token token, Module module) {
        if (module.imports().containsKey(token.text()))
            throw new FalconError(token, "A variable cannot use the module alias '" + token.text() + "'.");
    }
    private String qualify(String name, Module module) {
        return name == null ? null : module.namespace() + name;
    }
    private List<Parameter> qualify(List<Parameter> parameters, Module module) {
        return parameters.stream().map(parameter -> new Parameter(parameter.name(), parameter.type(),
                qualify(parameter.structName(), module))).toList();
    }

    String diagnostic(FalconError error, String fallbackFile, String fallbackSource) {
        String file = error.token.file().isEmpty() ? fallbackFile : error.token.file();
        return error.diagnostic(file, sources.getOrDefault(file, fallbackSource));
    }
}
