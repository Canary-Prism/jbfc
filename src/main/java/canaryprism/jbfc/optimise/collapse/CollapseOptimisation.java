package canaryprism.jbfc.optimise.collapse;

import canaryprism.jbfc.bf.BrainfuckInstruction;
import canaryprism.jbfc.optimise.Optimisation;

import java.util.LinkedList;
import java.util.List;

public final class CollapseOptimisation implements Optimisation<BrainfuckInstruction, CollapseInstruction> {
    
    @Override
    public Class<BrainfuckInstruction> getInput() {
        return BrainfuckInstruction.class;
    }
    
    @Override
    public Class<CollapseInstruction> getOutput() {
        return CollapseInstruction.class;
    }
    
    @Override
    public List<CollapseInstruction> optimise(List<BrainfuckInstruction> input) {
        var output = new LinkedList<CollapseInstruction>();
        
        for (int i = 0; i < input.size(); i++) {
            var collapse_instruction = switch (input.get(i)) {
                case BrainfuckInstruction.BasicInstruction instruction -> switch (instruction) {
                    case INCREMENT, DECREMENT -> {
                        var amount = (instruction == BrainfuckInstruction.BasicInstruction.INCREMENT)? 1 : -1;
                        
                        while (i + 1 < input.size()) {
                            var next = input.get(i + 1);
                            
                            if (next == BrainfuckInstruction.BasicInstruction.INCREMENT)
                                amount++;
                            else if (next == BrainfuckInstruction.BasicInstruction.DECREMENT)
                                amount--;
                            else
                                break;
                            
                            i++;
                        }
                        
                        yield new CollapseInstruction.Modify(amount);
                    }
                    case LEFT, RIGHT -> {
                        var amount = (instruction == BrainfuckInstruction.BasicInstruction.RIGHT)? 1 : -1;
                        
                        while (i + 1 < input.size()) {
                            var next = input.get(i + 1);
                            
                            if (next == BrainfuckInstruction.BasicInstruction.LEFT)
                                amount--;
                            else if (next == BrainfuckInstruction.BasicInstruction.RIGHT)
                                amount++;
                            else
                                break;
                            
                            i++;
                        }
                        
                        yield new CollapseInstruction.Move(amount);
                    }
                    case READ -> CollapseInstruction.Read.INSTANCE;
                    case WRITE -> CollapseInstruction.Write.INSTANCE;
                };
                case BrainfuckInstruction.LoopInstruction loop -> new CollapseInstruction.Loop(optimise(loop.instructions()));
            };
            output.add(collapse_instruction);
        }
        
        return output;
    }
    
    @Override
    public String getIdentifier() {
        return "collapse";
    }
}
