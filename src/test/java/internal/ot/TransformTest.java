package internal.ot;

import internal.ot.Operations.Op;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class TransformTest {

    @Test
    public void testConcurrentInsertConvergenceAtSamePosition() {
        // Requirement 7 scenario:
        // Start with: "apiVersion: v1"
        // Client A: insert " kind: Pod" at pos 14
        // Client B: insert " metadata:" at pos 14
        String initial = "apiVersion: v1";
        UUID userA = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID userB = UUID.fromString("00000000-0000-0000-0000-000000000002");

        Op opA = Op.insert(14, " kind: Pod");
        Op opB = Op.insert(14, " metadata:");

        // Case 1: Server processes OpA first, then transforms OpB against OpA
        String state1 = Transform.apply(initial, opA);
        Op opBPrime = Transform.transformSingle(opB, opA, userB, userA);
        String finalState1 = Transform.apply(state1, opBPrime);

        // Case 2: Server processes OpB first, then transforms OpA against OpB
        String state2 = Transform.apply(initial, opB);
        Op opAPrime = Transform.transformSingle(opA, opB, userA, userB);
        String finalState2 = Transform.apply(state2, opAPrime);

        // Convergence check: Both arrival orders MUST result in the exact same string
        assertEquals(finalState1, finalState2);
        assertTrue(finalState1.contains("kind: Pod"));
        assertTrue(finalState1.contains("metadata:"));
    }

    @Test
    public void testInsertVsInsertDifferentPositions() {
        String initial = "hello world";
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();

        Op opA = Op.insert(5, " beautiful");
        Op opB = Op.insert(0, "Say: ");

        Op opBPrime = Transform.transformSingle(opB, opA, userB, userA);
        assertEquals(0, opBPrime.getPosition());

        Op opAPrime = Transform.transformSingle(opA, opB, userA, userB);
        assertEquals(5 + "Say: ".length(), opAPrime.getPosition());

        String res1 = Transform.apply(Transform.apply(initial, opA), opBPrime);
        String res2 = Transform.apply(Transform.apply(initial, opB), opAPrime);
        assertEquals(res1, res2);
        assertEquals("Say: hello beautiful world", res1);
    }

    @Test
    public void testInsertVsDelete() {
        String initial = "abcdef";
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();

        // Delete "bc" at pos 1, length 2
        Op del = Op.delete(1, 2);
        // Insert "X" at pos 4
        Op ins = Op.insert(4, "X");

        Op insPrime = Transform.transformSingle(ins, del, userA, userB);
        // Insertion should shift left by 2 -> pos 2
        assertEquals(2, insPrime.getPosition());

        String s1 = Transform.apply(Transform.apply(initial, del), insPrime);
        Op delPrime = Transform.transformSingle(del, ins, userB, userA);
        String s2 = Transform.apply(Transform.apply(initial, ins), delPrime);

        assertEquals(s1, s2);
        assertEquals("adXef", s1);
    }

    @Test
    public void testDeleteVsDeleteOverlapping() {
        String initial = "0123456789";
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();

        Op del1 = Op.delete(2, 4); // deletes "2345"
        Op del2 = Op.delete(4, 4); // deletes "4567"

        Op del1Prime = Transform.transformSingle(del1, del2, userA, userB);
        Op del2Prime = Transform.transformSingle(del2, del1, userB, userA);

        String s1 = Transform.apply(Transform.apply(initial, del1), del2Prime);
        String s2 = Transform.apply(Transform.apply(initial, del2), del1Prime);

        assertEquals(s1, s2);
        assertEquals("0189", s1);
    }
}
