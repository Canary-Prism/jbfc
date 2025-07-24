package canaryprism.jbfc.optimise.flow;

import canaryprism.jbfc.optimise.Optimisation;
import canaryprism.jbfc.optimise.collapse.CollapseInstruction;

import java.util.*;
import java.util.stream.Stream;

public class FlowOptimisation implements Optimisation<CollapseInstruction, FlowInstruction> {
    
    @Override
    public Class<CollapseInstruction> getInput() {
        return CollapseInstruction.class;
    }
    
    @Override
    public Class<FlowInstruction> getOutput() {
        return FlowInstruction.class;
    }
    
    @Override
    public List<FlowInstruction> optimise(List<CollapseInstruction> input) {
        var output = new LinkedList<FlowInstruction>();
        
        for (var collapseInstruction : input) {
            var instruction = switch (collapseInstruction) {
                case CollapseInstruction.Write _ -> FlowInstruction.Write.INSTANCE;
                case CollapseInstruction.Read _ -> FlowInstruction.Read.INSTANCE;
                case CollapseInstruction.Modify(var amount) -> {
                    var last = output.peekLast();
                    
                    if (last instanceof FlowInstruction.Set(var last_amount)) {
                        output.removeLast();
                        yield new FlowInstruction.Set(last_amount + amount);
                    }
                    if (last instanceof FlowInstruction.Loop || last instanceof FlowInstruction.Transfer)
                        yield new FlowInstruction.Set(amount);
                    
                    yield new FlowInstruction.Modify(amount);
                }
                case CollapseInstruction.Move(var amount) -> new FlowInstruction.Move(amount);
                case CollapseInstruction.Loop loop -> {
                    var instructions = optimise(loop.instructions());
                    if (getTotalMovement(instructions) == 0 && isModifyMove(instructions)) {
                        var targets = getTransferTargets(instructions);
                        if (targets.getOrDefault(0, 0) instanceof Integer origin_modification && origin_modification != 0) {
                            if (targets.size() != 1) {
                                // transfer
                                if (origin_modification == -1)
                                    yield new FlowInstruction.Transfer(targets.entrySet()
                                            .stream()
                                            .filter((e) -> e.getKey() != 0)
                                            .map((e) -> new FlowInstruction.Transfer.Target(e.getKey(), e.getValue()))
                                            .toList());
                            } else {
                                // set
                                
                                /*
                                 * here i'll only hardcode to Set 0 because when a Modify is read it collapses the last output instruction
                                 * if it is a Set and turns itself into a Set if it's a Loop or Transfer
                                 */
                                yield new FlowInstruction.Set(0);
                            }
                        }
                    } else {
                        if (isModifyMove(instructions)) {
                            int move = getTotalMovement(instructions);
                            if (move != 0 && getTransferTargets(instructions).isEmpty()) {
                                yield new FlowInstruction.FindZero(move);
                            }
                        }
                    }
                    
                    yield new FlowInstruction.Loop(instructions);
                }
            };
            
            output.add(instruction);
        }
        
        return output;
    }
    
    private int getTotalMovement(List<FlowInstruction> instructions) {
        return instructions.stream()
                .flatMap((e) -> Stream.ofNullable((e instanceof FlowInstruction.Move(var amount)) ? amount : null))
                .reduce(0, Integer::sum);
    }
    
    private boolean isModifyMove(List<FlowInstruction> instructions) {
        return instructions.stream()
                .noneMatch((e) -> !(e instanceof FlowInstruction.Modify) && !(e instanceof FlowInstruction.Move));
    }
    
    private Map<Integer, Integer> getTransferTargets(List<FlowInstruction> instructions) {
        var map = new HashMap<Integer, Integer>();
        var offset = 0;
        for (var instruction : instructions) {
            switch (instruction) {
                case FlowInstruction.Modify(var amount) -> map.compute(offset, (_, e) -> (e != null) ? e + amount : amount);
                case FlowInstruction.Move(var amount) -> offset += amount;
                default -> throw new IllegalArgumentException("non modify-move instruction list");
            }
        }
        return map;
    }
    
    @Override
    public String getIdentifier() {
        return "flow";
    }
}
