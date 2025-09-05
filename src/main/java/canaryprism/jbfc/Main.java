package canaryprism.jbfc;

import canaryprism.jbfc.bf.BrainfuckInstruction;
import canaryprism.jbfc.optimise.Optimisation;
import picocli.CommandLine;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.TypeKind;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.ServiceLoader;
import java.util.function.Consumer;

import static picocli.CommandLine.Help.Ansi.AUTO;

@CommandLine.Command
public class Main implements Runnable {
    
    @CommandLine.Option(names = { "-d", "--output-path" }, description = "specify where to place generated class files")
    private Path output_path = Path.of(".");
    
    @CommandLine.Option(names = { "-x", "--extension-path" },
            description = "specify paths to jar files that jbfc should load to discover optimisations")
    private List<Path> extension_paths = List.of();
    
    @CommandLine.Option(names = { "-o", "--optimise" }, description = "the level of optimisation to use ('none' for no optimisation)")
    private String optimise = "state";
    
    @CommandLine.Option(names = { "-w", "--write-instructions" }, description = "write the final instruction list the compiler will write for every input file")
    private boolean write_instructions = false;
    
    @CommandLine.Parameters
    private List<Path> input_paths;
    
    private final ArrayDeque<Optimisation<?, ?>> optimisations = new ArrayDeque<>();
    
    @Override
    public void run() {
        if (input_paths.stream()
                .map(Path::getFileName)
                .distinct()
                .count() != input_paths.size()) {
            System.out.println(AUTO.string("@|red Error: duplicate filenames found|@"));
            return;
        }
        
        find_optimisation:
        try (var extension_classloader = new URLClassLoader(extension_paths.stream()
                .map(Path::toUri)
                .map((e) -> {
                    try {
                        return e.toURL();
                    } catch (MalformedURLException ex) {
                        throw new RuntimeException(ex);
                    }
                })
                .toArray(URL[]::new))) {
            
            if (optimise.equals("none"))
                break find_optimisation;
            
            var extension_loader = ServiceLoader.load(Optimisation.class, extension_classloader);
            var loader = ServiceLoader.load(Optimisation.class);
            
            var optimisation = extension_loader.stream()
                    .map(ServiceLoader.Provider::get)
                    .filter((e) -> e.getIdentifier().equalsIgnoreCase(optimise))
                    .findFirst()
                    .or(loader.stream()
                            .map(ServiceLoader.Provider::get)
                            .filter((e) -> e.getIdentifier().equalsIgnoreCase(optimise))
                            ::findFirst)
                    .orElseThrow(() -> new NoSuchElementException("optimisation '" + optimise + "' not found"));
            
            optimisations.push(optimisation);
            var dependency = optimisation.getInput();
            
            while (dependency != BrainfuckInstruction.class) {
                var final_dependency = dependency;
                optimisation = extension_loader.stream()
                        .map(ServiceLoader.Provider::get)
                        .filter((e) -> e.getOutput().equals(final_dependency))
                        .findFirst()
                        .or(loader.stream()
                                .map(ServiceLoader.Provider::get)
                                .filter((e) -> e.getOutput().equals(final_dependency))
                                ::findFirst)
                        .orElseThrow(() -> new NoSuchElementException("optimisation " + optimise + "not found"));
                
                optimisations.push(optimisation);
                
                dependency = optimisation.getInput();
                
            }
            
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        
        var classfile = ClassFile.of(ClassFile.DeadCodeOption.PATCH_DEAD_CODE);
        
        for (var input : input_paths) {
            var instructions = optimise(read(input));
            
            if (write_instructions)
                for (var instruction : instructions) {
                    System.out.println(instruction);
                }
            
            var data = classfile.build(ClassDesc.of(input.getFileName().toString().replace(".", "_")), (class_builder) -> {
                
                var self = ClassDesc.of(input.getFileName().toString().replace(".", "_"));
                
                var array = new Instruction.Array() {
                    @Override
                    public Consumer<CodeBuilder.BlockCodeBuilder> load() {
                        return (builder) -> builder
                                .getstatic(self, "array", int[].class.describeConstable().orElseThrow());
                    }
                    
                    @Override
                    public Consumer<CodeBuilder.BlockCodeBuilder> loadIndex(Instruction.Pointer pointer) {
                        return (builder) -> builder
                                .getstatic(self, "array", int[].class.describeConstable().orElseThrow())
                                .block(pointer.load())
                                .iaload();
                    }
                    
                    @Override
                    public Consumer<CodeBuilder.BlockCodeBuilder> storeIndex(Instruction.Pointer pointer, Instruction.Value value) {
                        return (builder) -> builder
                                .getstatic(self, "array", int[].class.describeConstable().orElseThrow())                                .block(pointer.load())
                                .block(value.load())
                                .iastore();
                    }
                    
                    @Override
                    public Consumer<CodeBuilder.BlockCodeBuilder> incIndex(Instruction.Pointer pointer, Instruction.Value amount) {
                        return (builder) -> builder
                                .getstatic(self, "array", int[].class.describeConstable().orElseThrow())                                .block(pointer.load())
                                .dup2()
                                .iaload()
                                .block(amount.load())
                                .iadd()
                                .iastore();
                    }
                };
                
                var pointer = new Instruction.Pointer() {
                    @Override
                    public Consumer<CodeBuilder.BlockCodeBuilder> load() {
                        return (builder) -> builder
                                .getstatic(self, "pointer", int.class.describeConstable().orElseThrow());
                    }
                    
                    @Override
                    public Consumer<CodeBuilder.BlockCodeBuilder> store(Instruction.Value value) {
                        return (builder) -> builder
                                .block(value.load())
                                .putstatic(self, "pointer", int.class.describeConstable().orElseThrow());                    }
                    
                    @Override
                    public Consumer<CodeBuilder.BlockCodeBuilder> inc(Instruction.Value amount) {
                        return (builder) -> builder
                                .block(amount.load())
                                .getstatic(self, "pointer", int.class.describeConstable().orElseThrow())                                .iadd()
                                .putstatic(self, "pointer", int.class.describeConstable().orElseThrow());
                    }
                    
                    @Override
                    public Consumer<CodeBuilder.BlockCodeBuilder> inc(int amount) {
                        return this.inc(() -> (builder) -> builder
                                .loadConstant(amount));
                    }
                };
                
                class_builder
                        .withField("array", int[].class.describeConstable().orElseThrow(), ClassFile.ACC_STATIC)
                        .withField("pointer", int.class.describeConstable().orElseThrow(), ClassFile.ACC_STATIC)
                        .withMethod("main", MethodTypeDesc.ofDescriptor("([Ljava/lang/String;)V"), AccessFlag.STATIC.mask(), (method_builder) -> method_builder
                                .withFlags(AccessFlag.PUBLIC, AccessFlag.STATIC)
                                .withCode((code_builder) -> {
                                    code_builder
                                            .loadConstant(80_000)
                                            .newarray(TypeKind.INT)
                                            .putstatic(self, "array", int[].class.describeConstable().orElseThrow())
                                            .loadConstant(40_000)
                                            .putstatic(self, "pointer", int.class.describeConstable().orElseThrow());
                                    for (var e : instructions) {
                                        e.writeCode(code_builder, self, array, pointer);
                                    }
                                    code_builder
                                            .return_();
                                }));
                for (var e : instructions) {
                    e.writeClass(class_builder, self, array, pointer);
                }
            });
            
            var output_path = this.output_path.resolve(input.getFileName().toString().replace(".", "_") + ".class");
            
            try {
                Files.write(output_path, data);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
    
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private List<? extends Instruction> optimise(List<? extends Instruction> instructions) {
        for (var e : optimisations) {
            instructions = ((Optimisation) e).optimise(instructions);
        }
        return instructions;
    }
    
    private List<? extends Instruction> read(Path path) {
        try (var is = Files.newInputStream(path)) {
            return BrainfuckInstruction.parse(is);
        } catch (IOException e) {
            throw new RuntimeException("failed to parse " + path, e);
        }
    }
    
    
    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }
}