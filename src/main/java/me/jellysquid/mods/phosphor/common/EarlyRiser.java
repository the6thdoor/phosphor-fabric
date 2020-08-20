package me.jellysquid.mods.phosphor.common;

import java.util.List;
import java.util.Iterator;

import com.chocohead.mm.api.ClassTinkerers;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;

import static org.objectweb.asm.Opcodes.*;

import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.AbstractInsnNode;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class EarlyRiser implements Runnable {
        public static final Logger LOGGER = LogManager.getLogger();

        public void run() {
                MappingResolver remapper = FabricLoader.getInstance().getMappingResolver();

                String lightStorage = remapper.mapClassName("intermediary", "net.minecraft.world.chunk.light.LightStorage");
                ClassTinkerers.addTransformation(lightStorage, node -> {
                        for (MethodNode mn : (List<MethodNode>) node.methods) {
                                if ("updateLightArrays".equals(mn.name)) {
                                        InsnList insns = mn.instructions;
                                        Iterator<AbstractInsnNode> j = insns.iterator();
                                        while (j.hasNext()) {
                                                AbstractInsnNode i1 = j.next();
                                                LOGGER.info(i1.getOpcode());
                                                if (i1.getOpcode() == INVOKEINTERFACE) {
                                                        AbstractInsnNode i2 = getNext(i1);
                                                        if (i2 != null && i2.getOpcode() == CHECKCAST) {
                                                                AbstractInsnNode i3 = getNext(i2);
                                                                if (i3 != null && i3.getOpcode() == INVOKEVIRTUAL) {
                                                                        InsnList il = new InsnList();
                                                                        il.add(new MethodInsnNode(INVOKEINTERFACE, "it/unimi/dsi/fastutil/longs/LongIterator", "nextLong", "()J"));
                                                                        insns.insert(i1.getPrevious(), il);
                                                                        insns.remove(i1);
                                                                        insns.remove(i2);
                                                                        insns.remove(i3);
                                                                }
                                                        }
                                                }
                                        }
                                }
                        }
                });
        }

        private static AbstractInsnNode getNext(AbstractInsnNode insn) {
                do {
                        insn = insn.getNext();
                        if (insn != null && !(insn instanceof LineNumberNode)) {
                                break;
                        }
                } while (insn != null);
                return insn;
        }
}
