package canaryprism.jbfc;

import java.lang.classfile.ClassBuilder;
import java.lang.classfile.CodeBuilder;
import java.lang.constant.ClassDesc;
import java.util.function.Consumer;

public interface Instruction {
    
    void writeCode(CodeBuilder code_builder, ClassDesc self, Array array, Pointer pointer, Input input, Output output);
    
    default void writeClass(ClassBuilder class_builder, ClassDesc self, Array array, Pointer pointer, Input input, Output output) {}
    
    interface Array {
        Consumer<CodeBuilder.BlockCodeBuilder> load();
        Consumer<CodeBuilder.BlockCodeBuilder> loadIndex(Pointer pointer);
        Consumer<CodeBuilder.BlockCodeBuilder> storeIndex(Pointer pointer, Value value);
        Consumer<CodeBuilder.BlockCodeBuilder> incIndex(Pointer pointer, Value amount);
    }
    
    interface Pointer {
        Consumer<CodeBuilder.BlockCodeBuilder> load();
        Consumer<CodeBuilder.BlockCodeBuilder> store(Value value);
        Consumer<CodeBuilder.BlockCodeBuilder> inc(Value amount);
        Consumer<CodeBuilder.BlockCodeBuilder> inc(int amount);
    }
    
    interface Value {
        Consumer<CodeBuilder.BlockCodeBuilder> load();
    }
    
    interface Output extends Value {
        default Consumer<CodeBuilder.BlockCodeBuilder> write(Array array, Pointer pointer) {
            return (builder) -> builder
                    .block(load())
                    .block(array.loadIndex(pointer))
                    .block(write());
        }
        Consumer<CodeBuilder.BlockCodeBuilder> write();
    }
    interface Input extends Value {
        default Consumer<CodeBuilder.BlockCodeBuilder> read(Array array, Pointer pointer) {
            return (builder) -> builder
                    .block(array.load())
                    .block(pointer.load())
                    .block(load())
                    .block(read())
                    .iastore();
        }
        Consumer<CodeBuilder.BlockCodeBuilder> read();
    }
}
