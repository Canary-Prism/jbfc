package canaryprism.jbfc.optimise.state;

import canaryprism.jbfc.Instruction;
import canaryprism.jbfc.optimise.Optimisation;
import canaryprism.jbfc.optimise.flow.FlowInstruction;
import org.apache.commons.lang3.ArrayUtils;

import java.io.*;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.TypeKind;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class StateOptimisation implements Optimisation<FlowInstruction, StateInstruction> {
    
    @Override
    public Class<FlowInstruction> getInput() {
        return FlowInstruction.class;
    }
    
    @Override
    public Class<StateInstruction> getOutput() {
        return StateInstruction.class;
    }
    
    static class State {
        
        int[] array = new int[80_000];
        int pointer = 40_000;
        boolean pointer_infected = false;
        List<Predicate<Integer>> infection = new LinkedList<>();
        
        Map<Integer, Integer> modify_tracker = new HashMap<>();
        int pointer_modify_tracker = 0;
        
        boolean isPointerSafe() {
            return !pointer_infected;
        }
        
        void flushChanges(List<StateInstruction> instructions) {
            var changes = modify_tracker.entrySet()
                    .stream()
                    .filter((e) -> !isInfected(e.getKey()))
                    .map((e) -> new StateInstruction.BulkSet.Entry(e.getKey(), e.getValue()))
                    .toList();
            if (changes.isEmpty())
                return;
            instructions.add(new StateInstruction.BulkSet(changes));
            
            if (pointer_modify_tracker != 0) {
                instructions.add(new StateInstruction.PointerSet(pointer));
                pointer_modify_tracker = 0;
            }
            
            modify_tracker.clear();
        }
        
        void movePointer(int amount) {
            pointer += amount;
            pointer_modify_tracker += amount;
        }
        
        void setPointer(int value) {
            var last_pointer = pointer;
            pointer = value;
            pointer_modify_tracker += value - last_pointer;
        }
        
        void setHere(int value) {
            if (array[pointer] == value)
                return;
            modify_tracker.put(pointer, value);
            array[pointer] = value;
        }
        
        void modifyHere(int amount) {
            if (amount == 0)
                return;
            modify_tracker.compute(pointer, (_, v) -> (v != null) ? v + amount : amount);
            array[pointer] = (array[pointer] + amount) & 255;
        }
        
        boolean isInfectedHere() {
            return isInfected(this.pointer);
        }
        
        boolean isInfected(int index) {
            return infection.stream()
                    .anyMatch((e) -> e.test(index));
        }
        
        @Override
        public State clone()  {
            try {
                var o = ((State) super.clone());
                
                o.infection = new ArrayList<>(infection);
                
                o.modify_tracker = new HashMap<>(modify_tracker);
                
                return o;
                
            } catch (CloneNotSupportedException e) {
                throw new RuntimeException(e);
            }
        }
    }
    
    @Override
    public List<StateInstruction> optimise(List<FlowInstruction> input) {
        var state = new State();
        
        if (input.stream().anyMatch((e) -> e instanceof FlowInstruction.Read))
            return optimise(input, state, false);
        else
            return List.of(interpret(input));
    }
    
    List<StateInstruction> optimise(List<FlowInstruction> input, State state, boolean loop) {
//        counter.start(input);
        var output = new LinkedList<StateInstruction>();
        
        for (var e : input) {
            if (state.isPointerSafe()) {
                switch (e) {
                    case FlowInstruction.Read _ -> {
                        var pointer = state.pointer;
                        state.infection.add((i) -> i == pointer);
                        
                        state.flushChanges(output);
                        
                        output.add(StateInstruction.Read.INSTANCE);
                    }
                    case FlowInstruction.Write _ -> {
                        if (state.isInfectedHere()) {
                            output.add(StateInstruction.Write.INSTANCE);
                        } else {
                            if (output.peekLast() instanceof StateInstruction.Print(var list)) {
                                list.add(((byte) state.array[state.pointer]));
                            } else {
                                output.add(new StateInstruction.Print(new LinkedList<>(List.of(((byte) state.array[state.pointer])))));
                            }
                        }
                    }
                    case FlowInstruction.Move(var amount) -> state.movePointer(amount);
                    case FlowInstruction.FindZero(var step) -> {
                        var start_pointer = state.pointer;
                        while (!state.isInfectedHere() && state.array[state.pointer] != 0) {
                            state.movePointer(step);
                        }
                        if (state.isInfectedHere()) {
                            state.pointer_infected = true; // welp, no more state tracking :p
                            
                            state.setPointer(start_pointer);
                            state.flushChanges(output);
                            
                            output.add(new StateInstruction.FindZero(step));
                        }
                    }
                    case FlowInstruction.Modify(var amount) -> {
                        if (state.isInfectedHere()) {
                            output.add(new StateInstruction.Modify(amount));
                        } else {
                            state.modifyHere(amount);
                        }
                    }
                    case FlowInstruction.Set(var value) -> {
                        if (state.isInfectedHere()) {
                            output.add(new StateInstruction.Set(value));
                        } else {
                            state.setHere(value);
                        }
                    }
                    case FlowInstruction.Transfer(var targets) -> {
                        if (state.isInfectedHere()) {
                            state.flushChanges(output);
                            for (var target : targets) {
                                var offset = target.offset();
                                state.infection.add((i) -> i == offset);
                            }
                            output.add(new StateInstruction.Transfer(targets.stream()
                                    .map((target) -> new StateInstruction.Transfer.Target(target.offset(), target.multiplier()))
                                    .toList()));
                        } else if (state.array[state.pointer] == 0) {
                            continue;
                        }
                        
                        var value = state.array[state.pointer];
                        
                        for (var target : targets) {
                            state.movePointer(target.offset());
                            if (state.isInfectedHere()) {
                                state.flushChanges(output);
                                output.add(new StateInstruction.Modify(value * target.multiplier()));
                            } else {
                                state.modifyHere(value * target.multiplier());
                            }
                            state.movePointer(-target.offset());
                        }
                        
                        state.setHere(0);
                    
                    }
                    case FlowInstruction.Loop(var instructions, var _) -> {
                        if (state.isInfectedHere()) {
                            state.pointer_infected = true; // welp, no more state tracking :p
                            state.flushChanges(output);
                        } else if (state.array[state.pointer] == 0) {
                            continue;
                        }
                        if (state.isPointerSafe()) {
                            output.addAll(optimise(instructions, state, true));
                        } else {
                            output.add(new StateInstruction.Loop(optimise(instructions, state, false)));
                        }
                    }
                }
            } else {
                var instruction = switch (e) {
                    case FlowInstruction.Read _ -> StateInstruction.Read.INSTANCE;
                    case FlowInstruction.Write _ -> StateInstruction.Write.INSTANCE;
                    case FlowInstruction.Move(var amount) -> new StateInstruction.Move(amount);
                    case FlowInstruction.Modify(var amount) -> new StateInstruction.Modify(amount);
                    case FlowInstruction.Set(var value) -> new StateInstruction.Set(value);
                    case FlowInstruction.Transfer(var targets) -> new StateInstruction.Transfer(targets.stream()
                            .map((target) -> new StateInstruction.Transfer.Target(target.offset(), target.multiplier()))
                            .toList());
                    case FlowInstruction.Loop(var instructions, var _) -> new StateInstruction.Loop(optimise(instructions, state, true));
                    case FlowInstruction.FindZero(var step) -> new StateInstruction.FindZero(step);
                };
                output.add(instruction);
            }
        }
        
        if (state.isPointerSafe() && loop) {
//            if we got through an entire loop here without infecting the pointer,
//            we need to run the loop contents here more times
//            until the current cell becomes 0
//            to do this we just run this method more times until it is
//            **or**
//            worst case scenario we end up infecting the pointer here which means we'll have to redo this loop since
//            all the constant folding we *thought* we could do are actually invalid
        
            while (state.isPointerSafe() && state.array[state.pointer] != 0) {
//                see here we run it without loop because otherwise it'd also do this loop and that'd be bad hehe
                var instructions = optimise(input, state, false);
                if (state.isPointerSafe()) {
//                    yippee
                    output.addAll(instructions);
                } else {
//                    noooo
                    output.add(new StateInstruction.Loop(optimise(input, state, false)));
                }
            }
            
            if (!state.isPointerSafe()) {
//                nooooo
//                rerun this exact method, but now state has an infected pointer so it will just
//                convert the instructions without attempting any constant propagation
//                whether loop is true or false doesn't matter
                return optimise(input, state, false);
            }
//            woohoo i guess
        }
        return output;
    }
    
    StateInstruction.Print interpret(List<FlowInstruction> input) {
        return new StateInstruction.Print(compile(input));
    }
    
    List<Byte> compile(List<FlowInstruction> instructions) {
        try {
            var zip_path = Files.createTempFile(null, ".zip");
            Files.deleteIfExists(zip_path);
            var classname = "State";
            try (var fs = FileSystems.newFileSystem(zip_path, Map.of("create", true))) {
                var root = fs.getPath("/");
                var classfile = ClassFile.of();
                var path = root.resolve(classname + ".class");
                var data = classfile.build(ClassDesc.of(classname), (class_builder) -> {
                    
                    var self = ClassDesc.of(classname);
                    
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
                    
                    var inputstream = new Instruction.Input() {
                        @Override
                        public Consumer<CodeBuilder.BlockCodeBuilder> read() {
                            return (builder) -> builder
                                    .invokevirtual(InputStream.class.describeConstable().orElseThrow(), "read",
                                            MethodTypeDesc.ofDescriptor("()I"));
                        }
                        
                        @Override
                        public Consumer<CodeBuilder.BlockCodeBuilder> load() {
                            return (builder) -> builder
                                    .getstatic(System.class.describeConstable().orElseThrow(), "in",
                                            InputStream.class.describeConstable().orElseThrow());
                        }
                    };
                    var outputstream = new Instruction.Output() {
                        @Override
                        public Consumer<CodeBuilder.BlockCodeBuilder> write() {
                            return (builder) -> builder
                                    .invokevirtual(OutputStream.class.describeConstable().orElseThrow(), "write",
                                            MethodTypeDesc.ofDescriptor("(I)V"));
                        }
                        
                        @Override
                        public Consumer<CodeBuilder.BlockCodeBuilder> load() {
                            return (builder) -> builder
                                    .getstatic(self, "output",
                                            PrintStream.class.describeConstable().orElseThrow());
                        }
                    };
                    
                    class_builder
                            .withField("array", int[].class.describeConstable().orElseThrow(), ClassFile.ACC_STATIC)
                            .withField("pointer", int.class.describeConstable().orElseThrow(), ClassFile.ACC_STATIC)
                            .withField("outputstream", OutputStream.class.describeConstable().orElseThrow(), ClassFile.ACC_STATIC | ClassFile.ACC_PUBLIC)
                            .withField("output", PrintStream.class.describeConstable().orElseThrow(), ClassFile.ACC_STATIC | ClassFile.ACC_PUBLIC)
                            .withMethod("main", MethodTypeDesc.ofDescriptor("([Ljava/lang/String;)V"), AccessFlag.STATIC.mask(), (method_builder) -> method_builder
                                    .withFlags(AccessFlag.PUBLIC, AccessFlag.STATIC)
                                    .withCode((code_builder) -> {
                                        code_builder
                                                .loadConstant(80_000)
                                                .newarray(TypeKind.INT)
                                                .putstatic(self, "array", int[].class.describeConstable().orElseThrow())
                                                .loadConstant(40_000)
                                                .putstatic(self, "pointer", int.class.describeConstable().orElseThrow())
                                                .new_(PrintStream.class.describeConstable().orElseThrow())
                                                .dup()
                                                .new_(ByteArrayOutputStream.class.describeConstable().orElseThrow())
                                                .dup()
                                                .dup()
                                                .invokespecial(ByteArrayOutputStream.class.describeConstable().orElseThrow(), "<init>", MethodTypeDesc.ofDescriptor("()V"))
                                                .putstatic(self, "outputstream", OutputStream.class.describeConstable().orElseThrow())
                                                .invokespecial(PrintStream.class.describeConstable().orElseThrow(), "<init>", MethodTypeDesc.ofDescriptor("(Ljava/io/OutputStream;)V"))
                                                .putstatic(self, "output", PrintStream.class.describeConstable().orElseThrow());
                                        for (var e : instructions) {
                                            e.writeCode(code_builder, self, array, pointer, inputstream, outputstream);
                                        }
                                        code_builder
                                                .return_();
                                    }));
                    for (var e : instructions) {
                        e.writeClass(class_builder, self, array, pointer, inputstream, outputstream);
                    }
                });
                
                Files.write(path, data);
                
            }
            try (var classloader = new URLClassLoader(new URL[] { zip_path.toUri().toURL() })) {
                var state = classloader.loadClass(classname);
                state.getMethod("main", String[].class).invoke(null, (Object) new String[0]);
                var outputstream = ((ByteArrayOutputStream) state.getField("outputstream").get(null));
                return List.of(ArrayUtils.toObject(outputstream.toByteArray()));
            } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException |
                     InvocationTargetException | NoSuchFieldException e) {
                throw new RuntimeException(e);
            } finally {
                Files.deleteIfExists(zip_path);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    
    @Override
    public String getIdentifier() {
        return "state";
    }
}
