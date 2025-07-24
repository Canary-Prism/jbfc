package canaryprism.jbfc.optimise;

import canaryprism.jbfc.Instruction;

import java.util.List;

public interface Optimisation<T extends Instruction, R extends Instruction> {
    Class<T> getInput();
    Class<R> getOutput();
    
    List<R> optimise(List<T> input);
    
    String getIdentifier();
}
