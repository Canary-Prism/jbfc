package canaryprism.jbfc.optimise.collapse;

import canaryprism.jbfc.Instruction;
import canaryprism.jbfc.bf.BrainfuckInstruction;

import java.lang.classfile.CodeBuilder;
import java.lang.constant.ClassDesc;
import java.util.List;

public sealed interface CollapseInstruction extends Instruction {
    
    enum Write implements CollapseInstruction {
        INSTANCE;
        
        @Override
        public void writeCode(CodeBuilder code_builder, ClassDesc self, Array array, Pointer pointer) {
            BrainfuckInstruction.BasicInstruction.WRITE.writeCode(code_builder, self, array, pointer);
        }
    }
    enum Read implements CollapseInstruction {
        INSTANCE;
        
        @Override
        public void writeCode(CodeBuilder code_builder, ClassDesc self, Array array, Pointer pointer) {
            BrainfuckInstruction.BasicInstruction.READ.writeCode(code_builder, self, array, pointer);
        }
    }
    
    record Modify(int amount) implements CollapseInstruction {
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
    
    record Move(int amount) implements CollapseInstruction {
        
        @Override
        public void writeCode(CodeBuilder code_builder, ClassDesc self, Array array, Pointer pointer) {
            code_builder
                    .block(pointer.inc(amount));
        }
    }
    
    record Loop(List<CollapseInstruction> instructions) implements CollapseInstruction {
        
        @Override
        public void writeCode(CodeBuilder code_builder, ClassDesc self, Array array, Pointer pointer) {
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
                    .labelBinding(right_label);
        }
    }
    
}
