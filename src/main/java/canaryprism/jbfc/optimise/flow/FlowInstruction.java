package canaryprism.jbfc.optimise.flow;

import canaryprism.jbfc.Instruction;
import canaryprism.jbfc.bf.BrainfuckInstruction;

import java.lang.classfile.*;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public sealed interface FlowInstruction extends Instruction {
    
    enum Write implements FlowInstruction {
        INSTANCE;
        
        @Override
        public void writeCode(CodeBuilder code_builder, ClassDesc self, Array array, Pointer pointer) {
            BrainfuckInstruction.BasicInstruction.WRITE.writeCode(code_builder, self, array, pointer);
        }
    }
    enum Read implements FlowInstruction {
        INSTANCE;
        
        @Override
        public void writeCode(CodeBuilder code_builder, ClassDesc self, Array array, Pointer pointer) {
            BrainfuckInstruction.BasicInstruction.READ.writeCode(code_builder, self, array, pointer);
        }
    }
    
    record Modify(int amount) implements FlowInstruction {
        public Modify {
            amount %= 256;
        }
        
        @Override
        public void writeCode(CodeBuilder code_builder, ClassDesc self, Array array, Pointer pointer) {
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
    
    record Set(int value) implements FlowInstruction {
        public Set {
            value &= 255;
        }
        
        @Override
        public void writeCode(CodeBuilder code_builder, ClassDesc self, Array array, Pointer pointer) {
            code_builder
                    .block(array.load())
                    .block(pointer.load())
                    .loadConstant(value)
                    .iastore();
        }
    }
    
    record Move(int amount) implements FlowInstruction {
        
        @Override
        public void writeCode(CodeBuilder code_builder, ClassDesc self, Array array, Pointer pointer) {
            code_builder
                    .block(pointer.inc(amount));
        }
    }
    
    record Transfer(List<Target> targets) implements FlowInstruction {
        
        public record Target(int offset, int multiplier) {
            public Target {
                if (offset == 0)
                    throw new IllegalArgumentException("offset can't be 0");
                if (multiplier == 0)
                    throw new IllegalArgumentException("multiplier can't be 0");
            }
        }
        
        public Transfer {
            targets = targets.stream()
                    .sorted(Comparator.comparing(Target::offset))
                    .toList();
        }
        
        @Override
        public void writeCode(CodeBuilder code_builder, ClassDesc self, Array array, Pointer pointer) {
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
    
    record Loop(List<FlowInstruction> instructions, String name) implements FlowInstruction {
        
        public Loop(List<FlowInstruction> instructions) {
            this(instructions, "loop" + UUID.randomUUID());
        }
        
        @Override
        public void writeCode(CodeBuilder code_builder, ClassDesc self, Array array, Pointer pointer) {
            code_builder
                    .invokestatic(self, name, MethodTypeDesc.ofDescriptor("()V"));
        }
        
        @Override
        public void writeClass(ClassBuilder class_builder, ClassDesc self, Array array, Pointer pointer) {
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
                                    instruction.writeCode(code_builder, self, array, pointer);
                                }

                                code_builder
                                        .goto_(left_label)
                                        .labelBinding(right_label)
                                        .return_();
                            }));
            
            for (var instruction : instructions) {
                instruction.writeClass(class_builder, self, array, pointer);
            }
        }
    }
    
    record FindZero(int step) implements FlowInstruction {
        
        @Override
        public void writeCode(CodeBuilder code_builder, ClassDesc self, Array array, Pointer pointer) {
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
}
