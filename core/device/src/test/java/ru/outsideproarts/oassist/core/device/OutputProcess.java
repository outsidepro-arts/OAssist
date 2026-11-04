package ru.outsideproarts.oassist.core.device;

public final class OutputProcess {
    public static void main(String[] args) throws Exception {
        byte[] block = new byte[8192];
        java.util.Arrays.fill(block, (byte) 'x');
        for (int i = 0; i < 32; i++) System.out.write(block);
        System.out.flush();
    }
}
