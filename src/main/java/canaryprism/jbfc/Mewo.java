package canaryprism.jbfc;

public class Mewo {
    int[] arr;
    int ptr;
    void mewo() {
        while (arr[ptr] != 0) {
            System.out.println(arr[ptr]);
            arr[ptr]--;
        }
        
        var value = arr[ptr];
        arr[ptr + 1] = arr[ptr + 1] + value * 2 & 255;
    }
}
