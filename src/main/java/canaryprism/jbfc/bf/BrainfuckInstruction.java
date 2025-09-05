package canaryprism.jbfc.bf;

import canaryprism.jbfc.Instruction;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.CodeBuilder;
import java.lang.constant.ClassDesc;
import java.util.ArrayList;
import java.util.List;

public sealed interface BrainfuckInstruction extends Instruction {
    
    enum BasicInstruction implements BrainfuckInstruction {
        INCREMENT, DECREMENT, LEFT, RIGHT, READ, WRITE;
        
        
        @Override
        public void writeCode(CodeBuilder code_builder, ClassDesc self, Array array, Pointer pointer, Input input, Output output) {
            switch (this) {
                case INCREMENT -> code_builder
                        .block(array.load())
                        .block(pointer.load())
                        .dup2()
                        .iaload()
                        .loadConstant(1)
                        .iadd()
                        .loadConstant(255)
                        .iand()
                        .iastore();
                
                case DECREMENT -> code_builder
                        .block(array.load())
                        .block(pointer.load())
                        .dup2()
                        .iaload()
                        .loadConstant(1)
                        .isub()
                        .loadConstant(255)
                        .iand()
                        .iastore();
                
                case LEFT -> code_builder
                        .block(pointer.inc(-1));
                case RIGHT -> code_builder
                        .block(pointer.inc(1));
                case WRITE -> code_builder
                        .block(output.write(array, pointer));
                case READ -> code_builder
                        .block(input.read(array, pointer));
            }
        }
    }
    
    record LoopInstruction(List<BrainfuckInstruction> instructions) implements BrainfuckInstruction {
        
        @Override
        public void writeCode(CodeBuilder code_builder, ClassDesc self, Array array, Pointer pointer, Input input, Output output) {
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
                    .labelBinding(right_label);
        }
    }
    
    static List<BrainfuckInstruction> parse(InputStream is) throws IOException {
        return parse(is, false);
    }
    
    private static List<BrainfuckInstruction> parse(InputStream is, boolean loop) throws IOException {
        var list = new ArrayList<BrainfuckInstruction>();
        
        int i;
        
        read_loop: {
            
            while ((i = is.read()) != -1) {
                var c = ((char) i);
                
                if (c == ']') {
                    if (!loop)
                        throw new IOException("extra unbalanced ] found");
                    break read_loop;
                }

                var instruction = switch (c) {
                    case '+' -> BasicInstruction.INCREMENT;
                    case '-' -> BasicInstruction.DECREMENT;
                    case '>' -> BasicInstruction.RIGHT;
                    case '<' -> BasicInstruction.LEFT;
                    case ',' -> BasicInstruction.READ;
                    case '.' -> BasicInstruction.WRITE;
                    case '[' -> new LoopInstruction(parse(is, true));
                    default -> null;
                };
                
                if (instruction == null)
                    continue;
                
                list.add(instruction);
            }
            
            if (loop)
                throw new IOException("expected ], found end of stream");
                
        }
        
        
        return list;
    }
}
