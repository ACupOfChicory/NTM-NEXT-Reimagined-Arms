package acup.gunfeel;

import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;

import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;

// Client: the render type of bullet holes. Like entity_translucent but it does not write depth (overlapping holes
// would hide each other's soft edges, flickering as the camera moves), with a small depth bias that keeps it on top
// of the face it lies on. Holes on glass (glass blocks, a door's clear window) are drawn "late", after the
// translucent terrain and the clouds and rain (Tracers.afterWeather), so what is seen through the glass behind them
// is already there.
// Not sorted by distance: holes overlap in one plane, and sorting their triangles by distance flips which hole's soft
// edge lies on top as the camera turns (patches of other shades along the triangles). They are drawn in the order
// they were made, a newer hole over an older one.
public final class HoleRender {
    public static final RenderPipeline PIPELINE = pipeline("pipeline/bullet_hole", false);
    // one-sided: holes on a full cube (copper grate), seen only from outside like the cube's own faces; from inside,
    // through its see-through pixels, the far face is not drawn and neither are its holes
    public static final RenderPipeline PIPELINE_CULL = pipeline("pipeline/bullet_hole_cull", true);

    private static RenderPipeline pipeline(String location, boolean cull) {
        return RenderPipeline.builder(entitySnippet())
            .withLocation(Identifier.fromNamespaceAndPath("ntmgunfeel", location))
            .withVertexShader(Identifier.fromNamespaceAndPath("ntmgunfeel", "core/bullet_hole"))
            .withShaderDefine("ALPHA_CUTOUT", 0.1F)
            .withShaderDefine("PER_FACE_LIGHTING")
            .withBindGroupLayout(BindGroupLayouts.SAMPLER1)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false, 1.0F, 10.0F))
            .withCull(cull)
            .build();
    }
    private static final Map<String, RenderType> TYPES = new HashMap<>();
    private static final java.util.Set<RenderType> LATE_TYPES = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    private HoleRender() {
    }

    public static void register(IEventBus modBus) {
        modBus.addListener(RegisterRenderPipelinesEvent.class, e -> {
            e.registerPipeline(PIPELINE);
            e.registerPipeline(PIPELINE_CULL);
        });
    }

    // late: drawn after the translucent terrain (holes on glass), see MixinSubmitNodeCollection
    public static RenderType type(Identifier texture, boolean late, boolean cull) {
        return TYPES.computeIfAbsent(texture + (late ? "|late" : "") + (cull ? "|cull" : ""), k -> {
            RenderSetup setup = RenderSetup.builder(cull ? PIPELINE_CULL : PIPELINE)
                    .withTexture("Sampler0", texture)
                    .useLightmap()
                    .useOverlay()
                    .createRenderSetup();
            RenderType type = create(late ? "ntmgunfeel_bullet_hole_late" : "ntmgunfeel_bullet_hole", setup);
            if (late) LATE_TYPES.add(type);
            return type;
        });
    }

    public static boolean isLate(RenderType type) {
        return !LATE_TYPES.isEmpty() && LATE_TYPES.contains(type);
    }

    // RenderPipelines.ENTITY_SNIPPET and RenderType.create are made public by NeoForge only at run time
    private static RenderPipeline.Snippet entitySnippet() {
        try {
            var f = RenderPipelines.class.getDeclaredField("ENTITY_SNIPPET");
            f.setAccessible(true);
            return (RenderPipeline.Snippet) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("no RenderPipelines.ENTITY_SNIPPET", e);
        }
    }

    private static RenderType create(String name, RenderSetup setup) {
        try {
            var m = RenderType.class.getDeclaredMethod("create", String.class, RenderSetup.class);
            m.setAccessible(true);
            return (RenderType) m.invoke(null, name, setup);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("no RenderType.create", e);
        }
    }
}
