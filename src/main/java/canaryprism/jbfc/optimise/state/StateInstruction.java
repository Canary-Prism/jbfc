package canaryprism.jbfc.optimise.state;

import canaryprism.jbfc.Instruction;

import java.io.PrintStream;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.TypeKind;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public sealed interface StateInstruction extends Instruction {
    
    enum Write implements StateInstruction {
        INSTANCE;
        
        @Override
        public void writeCode(CodeBuilder code_builder, ClassDesc self, Array array, Pointer pointer, Input input, Output output) {
            code_builder
                    .block(output.write(array, pointer));
        }
    }
    enum Read implements StateInstruction {
        INSTANCE;
        
        @Override
        public void writeCode(CodeBuilder code_builder, ClassDesc self, Array array, Pointer pointer, Input input, Output output) {
            code_builder
                    .block(input.read(array, pointer));
        }
    }
    
    record Modify(int amount) implements StateInstruction {
        public Modify {
            amount %= 256;
        }
        
        @Override
        public void writeCode(CodeBuilder code_builder, ClassDesc self, Array array, Pointer pointer, Input input, Output output) {
            code_builder
                    .block(array.load())
                    .block(pointer.load())
                    .dup2()
                    .iaload()
                    .loadConstant(amount)
                    .iadd()
                    .loadConstant(255)
                    .iand()
                    .iastore();
        }
    }
    
    record Set(int value) implements StateInstruction {
        public Set {
            value &= 255;
        }
        
        @Override
        public void writeCode(CodeBuilder code_builder, ClassDesc self, Array array, Pointer pointer, Input input, Output output) {
            code_builder
                    .block(array.load())
                    .block(pointer.load())
                    .loadConstant(value)
                    .iastore();
        }
    }
    
    record Move(int amount) implements StateInstruction {
        
        @Override
        public void writeCode(CodeBuilder code_builder, ClassDesc self, Array array, Pointer pointer, Input input, Output output) {
            code_builder
                    .block(pointer.inc(amount));
        }
    }
    
    record Transfer(List<Transfer.Target> targets) implements StateInstruction {
        
        record Target(int offset, int multiplier) {
            public Target {
                if (offset == 0)
                    throw new IllegalArgumentException("offset can't be 0");
                if (multiplier == 0)
                    throw new IllegalArgumentException("multiplier can't be 0");
            }
        }
        
        public Transfer {
            targets = targets.stream()
                    .sorted(Comparator.comparing(Transfer.Target::offset))
                    .toList();
        }
        
        @Override
        public void writeCode(CodeBuilder code_builder, ClassDesc self, Array array, Pointer pointer, Input input, Output output) {
            code_builder.block((block_builder) -> {
                
                //             dam complicated instruction to implement mm
                block_builder
                        .block(array.load())
                        .block(pointer.load());
                
                var value = block_builder.allocateLocal(TypeKind.INT);
                
                block_builder
                        .dup2()
                        .iaload()
                        .istore(value);
                
                for (var target : targets) {
                    block_builder
                            .dup2()
                            .loadConstant(target.offset())
                            .iadd()
                            .dup2()
                            .iaload()
                            .iload(value);
                    if (target.multiplier() != 0)
                        block_builder
                                .loadConstant(target.multiplier())
                                .imul();
                    block_builder
                            .iadd()
                            .loadConstant(255)
                            .iand()
                            .iastore();
                }
                
                block_builder
                        .loadConstant(0)
                        .iastore();
            });
            
        }
        
    }
    
    record Loop(List<StateInstruction> instructions, String name) implements StateInstruction {
        
        public Loop(List<StateInstruction> instructions) {
            this(instructions, "loop" + UUID.randomUUID());
        }
        
        @Override
        public void writeCode(CodeBuilder code_builder, ClassDesc self, Array array, Pointer pointer, Input input, Output output) {
            code_builder
                    .invokestatic(self, name, MethodTypeDesc.ofDescriptor("()V"));
        }
        
        @Override
        public void writeClass(ClassBuilder class_builder, ClassDesc self, Array array, Pointer pointer, Input input, Output output) {
            class_builder
                    .withMethod(name, MethodTypeDesc.ofDescriptor("()V"), ClassFile.ACC_STATIC, (method_builder) -> method_builder
                            .withCode((code_builder) -> {
                                var right_label = code_builder.newLabel();
                                var left_label = code_builder.newLabel();
                                code_builder
                                        .labelBinding(left_label)
                                        .block(array.load())
                                        .block(pointer.load())
                                        .iaload()
                                        .ifeq(right_label);
                                
                                for (var instruction : instructions) {
                                    instruction.writeCode(code_builder, self, array, pointer, input, output);
                                }
                                
                                code_builder
                                        .goto_(left_label)
                                        .labelBinding(right_label)
                                        .return_();
                            }));
            
            for (var instruction : instructions) {
                instruction.writeClass(class_builder, self, array, pointer, input, output);
            }
        }
    }
    
    record FindZero(int step) implements StateInstruction {
        
        @Override
        public void writeCode(CodeBuilder code_builder, ClassDesc self, Array array, Pointer pointer, Input input, Output output) {
            var start = code_builder.newLabel();
            var end = code_builder.newLabel();
            
            code_builder
                    .labelBinding(start)
                    .block(array.loadIndex(pointer))
                    .ifeq(end)
                    .block(pointer.inc(step))
                    .goto_(start)
                    .labelBinding(end);
        }
    }
    
    record Print(List<Character> chars) implements StateInstruction {
        
        @Override
        public void writeCode(CodeBuilder code_builder, ClassDesc self, Array array, Pointer pointer, Input input, Output output) {
            if (chars.size() == 1) {
                code_builder
                        .block(output.load())
                        .loadConstant(chars.getFirst())
                        .block(output.write());
            } else {
                var sb = new StringBuilder();
                for (var e : chars)
                    sb.append(e.charValue());
                code_builder
                        .block(output.load())
                        .loadConstant(sb.toString())
                        .invokevirtual(PrintStream.class.describeConstable().orElseThrow(), "print",
                                MethodTypeDesc.ofDescriptor("(Ljava/lang/String;)V"));
            }
        }
    }
    
    record BulkSet(List<Entry> entries) implements StateInstruction {
        
        record Entry(int index, int value) {
            
            public Entry {
                value &= 255;
            }
        }
        
        @Override
        public void writeCode(CodeBuilder code_builder, ClassDesc self, Array array, Pointer pointer, Input input, Output output) {
            for (var entry : entries) {
                pointer = new Pointer() {
                    @Override
                    public Consumer<CodeBuilder.BlockCodeBuilder> load() {
                        return (code_builder) -> code_builder
                                .loadConstant(entry.index());
                    }
                    
                    @Override
                    public Consumer<CodeBuilder.BlockCodeBuilder> store(Value value) {
                        return null;
                    }
                    
                    @Override
                    public Consumer<CodeBuilder.BlockCodeBuilder> inc(Value amount) {
                        return null;
                    }
                    
                    @Override
                    public Consumer<CodeBuilder.BlockCodeBuilder> inc(int amount) {
                        return null;
                    }
                };
                code_builder
                        .block(array.storeIndex(pointer, () -> (e) -> e
                                .loadConstant(entry.value())));
            }
        }
        
    }
    
    record PointerSet(int value) implements StateInstruction {
        
        @Override
        public void writeCode(CodeBuilder code_builder, ClassDesc self, Array array, Pointer pointer, Input input, Output output) {
            code_builder
                    .block(pointer.store(() -> (e) -> e.loadConstant(value)));
        }
    }
}
