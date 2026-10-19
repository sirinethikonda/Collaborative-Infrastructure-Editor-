package internal.ot;

import internal.ot.Operations.Op;

import java.util.UUID;

public class Transform {

    public static class TransformResult {
        public final Op op1Prime;
        public final Op op2Prime;

        public TransformResult(Op op1Prime, Op op2Prime) {
            this.op1Prime = op1Prime;
            this.op2Prime = op2Prime;
        }
    }

    /**
     * Transforms an incoming operation op against a previously applied operation concurrentOp.
     * userId is the author of incoming op, concurrentUserId is author of concurrentOp.
     * Returns the transformed op to be applied to the current server document.
     */
    public static Op transformSingle(Op op, Op concurrentOp, UUID userId, UUID concurrentUserId) {
        if (op == null || op.isNoop()) {
            return Op.noop();
        }
        if (concurrentOp == null || concurrentOp.isNoop()) {
            return copy(op);
        }

        if (op.isInsert() && concurrentOp.isInsert()) {
            return transformInsertVsInsert(op, concurrentOp, userId, concurrentUserId);
        } else if (op.isInsert() && concurrentOp.isDelete()) {
            return transformInsertVsDelete(op, concurrentOp);
        } else if (op.isDelete() && concurrentOp.isInsert()) {
            return transformDeleteVsInsert(op, concurrentOp);
        } else if (op.isDelete() && concurrentOp.isDelete()) {
            return transformDeleteVsDelete(op, concurrentOp);
        }

        return copy(op);
    }

    /**
     * Dual transformation: T(opA, opB) -> (opA', opB')
     */
    public static TransformResult transform(Op opA, Op opB, UUID userA, UUID userB) {
        Op opAPrime = transformSingle(opA, opB, userA, userB);
        Op opBPrime = transformSingle(opB, opA, userB, userA);
        return new TransformResult(opAPrime, opBPrime);
    }

    private static Op transformInsertVsInsert(Op op1, Op op2, UUID user1, UUID user2) {
        int pos1 = op1.getPosition();
        int pos2 = op2.getPosition();
        int len2 = (op2.getValue() != null) ? op2.getValue().length() : 0;

        if (pos1 < pos2) {
            return Op.insert(pos1, op1.getValue());
        } else if (pos1 > pos2) {
            return Op.insert(pos1 + len2, op1.getValue());
        } else {
            // Simultaneous insert at exact same index: deterministic tie-breaker using userId
            int cmp = 0;
            if (user1 != null && user2 != null) {
                cmp = user1.compareTo(user2);
            }
            if (cmp > 0) {
                return Op.insert(pos1 + len2, op1.getValue());
            } else {
                return Op.insert(pos1, op1.getValue());
            }
        }
    }

    private static Op transformInsertVsDelete(Op ins, Op del) {
        int insPos = ins.getPosition();
        int delPos = del.getPosition();
        int delLen = del.getLength();

        if (insPos <= delPos) {
            return Op.insert(insPos, ins.getValue());
        } else if (insPos >= delPos + delLen) {
            return Op.insert(insPos - delLen, ins.getValue());
        } else {
            // Insertion happened inside the deleted segment
            return Op.insert(delPos, ins.getValue());
        }
    }

    private static Op transformDeleteVsInsert(Op del, Op ins) {
        int delPos = del.getPosition();
        int delLen = del.getLength();
        int insPos = ins.getPosition();
        int insLen = (ins.getValue() != null) ? ins.getValue().length() : 0;

        if (delPos + delLen <= insPos) {
            return Op.delete(delPos, delLen);
        } else if (delPos >= insPos) {
            return Op.delete(delPos + insLen, delLen);
        } else {
            // Insert occurred inside the delete range: extend delete length
            return Op.delete(delPos, delLen + insLen);
        }
    }

    private static Op transformDeleteVsDelete(Op del1, Op del2) {
        int pos1 = del1.getPosition();
        int len1 = del1.getLength();
        int pos2 = del2.getPosition();
        int len2 = del2.getLength();

        int end1 = pos1 + len1;
        int end2 = pos2 + len2;

        if (end1 <= pos2) {
            // del1 is entirely before del2
            return Op.delete(pos1, len1);
        } else if (pos1 >= end2) {
            // del1 is entirely after del2
            return Op.delete(pos1 - len2, len1);
        } else if (pos1 >= pos2 && end1 <= end2) {
            // del1 is completely within del2 (already deleted)
            return Op.noop();
        } else if (pos1 <= pos2 && end1 >= end2) {
            // del1 completely covers del2
            return Op.delete(pos1, len1 - len2);
        } else if (pos1 < pos2 && end1 <= end2) {
            // del1 overlaps start of del2
            return Op.delete(pos1, pos2 - pos1);
        } else {
            // del1 overlaps end of del2
            return Op.delete(pos2, end1 - end2);
        }
    }

    /**
     * Applies an operation to string content and returns the resulting content.
     */
    public static String apply(String content, Op op) {
        if (content == null) {
            content = "";
        }
        if (op == null || op.isNoop()) {
            return content;
        }

        int docLen = content.length();
        int pos = Math.max(0, Math.min(op.getPosition(), docLen));

        if (op.isInsert()) {
            String val = (op.getValue() != null) ? op.getValue() : "";
            return content.substring(0, pos) + val + content.substring(pos);
        } else if (op.isDelete()) {
            int len = op.getLength();
            if (len <= 0) {
                return content;
            }
            int end = Math.min(pos + len, docLen);
            return content.substring(0, pos) + content.substring(end);
        }

        return content;
    }

    private static Op copy(Op op) {
        return new Op(op.getType(), op.getPosition(), op.getValue(), op.getLength());
    }
}
