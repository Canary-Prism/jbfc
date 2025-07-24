package canaryprism.jbfc;

import java.lang.classfile.ClassBuilder;
import java.lang.classfile.CodeBuilder;
import java.lang.constant.ClassDesc;
import java.util.function.Consumer;

public interface Instruction {
    
    void writeCode(CodeBuilder code_builder, ClassDesc self, Array array, Pointer pointer);
    
    default void writeClass(ClassBuilder class_builder, ClassDesc self, Array array, Pointer pointer) {}
    
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
}
