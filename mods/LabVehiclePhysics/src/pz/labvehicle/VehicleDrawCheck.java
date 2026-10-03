package pz.labvehicle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Lab instrument: why a 3D renderer may skip a vehicle. Reads only the game's own data.
 *
 * A 3D renderer that draws vehicles from the game's model slots needs each model ready to draw: a
 * loaded mesh with a vertex buffer that has positions and texture coordinates, a shader, a
 * vehicle-shader model on the vehicle's own VehicleModelInstance, and for a skinned (static = FALSE)
 * model a bone palette. In the lab, Viewpoint left out whole tracked vehicles of Military Tool Kit
 * while one of their models failed this (the fake_wheel placeholder, see Patch_placeholderModels).
 * The game builds the palette in AnimatedModelInstanceRenderData.initMatrixPalette: once the part's
 * AnimationPlayer is ready it is cut to the length of getSkinTransforms(model.tag), and for a model
 * without skinning data (tag == null) that is the player's own transforms, which can be empty.
 *
 * Every PERIOD_NS, for each vehicle with an active model slot, each model (the body, the parts,
 * the muzzle flashes) is listed with those facts; a line is written for a tracked vehicle
 * (category tracked_armour) or for any vehicle with a suspicious model, and only when it changed.
 * Debug log only (lab build or -Dlabvehicle.verbose=true).
 */
public final class VehicleDrawCheck {

    private VehicleDrawCheck() {
    }

    public static final long PERIOD_NS = 3_000_000_000L;

    public static volatile boolean broken = false;
    public static final Map<Object, Long> LAST = new WeakHashMap<Object, Long>();
    public static final Map<Object, String> REPORTED = new WeakHashMap<Object, String>();

    public static Field fSprite, fSlot, fSlotActive, fSlotModel, fSlotSub, fSlotMuzzle;
    public static Field fInstModel, fInstParent, fInstAnim, fModelName, fModelMesh, fModelEffect, fModelTag, fModelStatic;
    public static Field fMeshVb, fVbFormat, fElType, fModelTransforms, fServer;
    public static Method mScriptName, mMeshReady, mFmtCount, mFmtElement, mShaderName, mVehicleShader, mAnimReady, mNumBones;
    public static Class<?> cVehicleInstance;

    public static void maybe(Object vehicle) {
        if (!Log.VERBOSE || broken || vehicle == null) {
            return;
        }
        long now = System.nanoTime();
        Long last = LAST.get(vehicle);
        if (last != null && now - last.longValue() < PERIOD_NS) {
            return;
        }
        LAST.put(vehicle, Long.valueOf(now));
        try {
            if (mScriptName == null) {
                init(vehicle);
            }
            if (fServer.getBoolean(null)) {
                return;
            }
            Object sprite = fSprite.get(vehicle);
            Object slot = sprite == null ? null : fSlot.get(sprite);
            if (slot == null || !fSlotActive.getBoolean(slot)) {
                return;
            }
            String name = (String) mScriptName.invoke(vehicle);
            VehicleCfg.Rule rule = VehicleCfg.forName(name);
            boolean tracked = rule != null && TrackFootprint.CATEGORY.equalsIgnoreCase(rule.category);
            StringBuilder sb = new StringBuilder();
            int[] suspicious = new int[1];
            describe(sb, fSlotModel.get(slot), "body", suspicious);
            for (Object o : (List<?>) fSlotSub.get(slot)) {
                describe(sb, o, "part", suspicious);
            }
            for (Object o : (List<?>) fSlotMuzzle.get(slot)) {
                describe(sb, o, "muzzle", suspicious);
            }
            String report = sb.toString();
            if ((tracked || suspicious[0] > 0) && !report.equals(REPORTED.get(vehicle))) {
                REPORTED.put(vehicle, report);
                Log.debug("[LabVehiclePhysics] draw check " + name + ": " + suspicious[0] + " suspicious model(s)" + report);
            }
        } catch (Throwable t) {
            broken = true;
            Log.info("[LabVehiclePhysics] ERROR in the draw check, disabling it: " + t);
        }
    }

    private static void describe(StringBuilder sb, Object inst, String role, int[] suspicious) throws Exception {
        if (inst == null) {
            return;
        }
        Object model = fInstModel.get(inst);
        if (model == null) {
            sb.append("\n    ").append(role).append(" ! no model");
            suspicious[0]++;
            return;
        }
        String modelName = String.valueOf(fModelName.get(model));
        boolean isStatic = fModelStatic.getBoolean(model);
        Object mesh = fModelMesh.get(model);
        Object vb = mesh == null ? null : fMeshVb.get(mesh);
        boolean ready = mesh != null && ((Boolean) mMeshReady.invoke(mesh)).booleanValue();
        boolean pos = false, uv = false;
        if (vb != null) {
            Object fmt = fVbFormat.get(vb);
            int n = ((Integer) mFmtCount.invoke(fmt)).intValue();
            for (int i = 0; i < n; i++) {
                String type = String.valueOf(fElType.get(mFmtElement.invoke(fmt, Integer.valueOf(i))));
                pos |= "VertexArray".equals(type);
                uv |= "TextureCoordArray".equals(type);
            }
        }
        Object effect = fModelEffect.get(model);
        String shader = effect == null ? "-" : String.valueOf(mShaderName.invoke(effect));
        boolean vehicleShader = effect != null && ((Boolean) mVehicleShader.invoke(effect)).booleanValue();
        Object parent = fInstParent.get(inst);
        boolean onBody = cVehicleInstance.isInstance(inst) || cVehicleInstance.isInstance(parent);
        String skin = "static";
        boolean emptyPalette = false;
        if (!isStatic) {
            Object tag = fModelTag.get(model);
            Object anim = fInstAnim.get(inst);
            boolean animReady = anim != null && ((Boolean) mAnimReady.invoke(anim)).booleanValue();
            if (tag != null && mNumBones != null && mNumBones.getDeclaringClass().isInstance(tag)) {
                skin = "skinned " + mNumBones.invoke(tag) + " bones, animator " + (anim == null ? "none" : animReady ? "ready" : "not ready");
            } else {
                Object[] own = anim == null ? null : (Object[]) fModelTransforms.get(anim);
                int len = own == null ? -1 : own.length;
                skin = "NO SKINNING DATA, animator " + (anim == null ? "none" : animReady ? "ready" : "not ready")
                        + ", its own transforms " + len;
                emptyPalette = animReady && len == 0;
            }
        }
        StringBuilder bad = new StringBuilder();
        if (vb == null) {
            bad.append(" no-vertex-buffer");
        }
        if (!ready) {
            bad.append(" mesh-not-ready");
        }
        if (vb != null && !pos) {
            bad.append(" no-positions");
        }
        if (vb != null && !uv) {
            bad.append(" no-uv");
        }
        if (effect == null) {
            bad.append(" no-shader");
        }
        if (vehicleShader && !onBody) {
            bad.append(" vehicle-shader-off-body");
        }
        if (emptyPalette) {
            bad.append(" empty-bone-palette");
        }
        if (bad.length() > 0) {
            suspicious[0]++;
        }
        sb.append("\n    ").append(bad.length() > 0 ? "!! " : "   ").append(role).append(' ').append(modelName)
                .append(" | shader ").append(shader).append(onBody ? "" : " (not on the body)")
                .append(" | ").append(skin)
                .append(bad.length() > 0 ? " |" + bad : "");
    }

    private static Field field(Class<?> c, String name) throws NoSuchFieldException {
        Class<?> k = c;
        while (k != null) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                k = k.getSuperclass();
            }
        }
        throw new NoSuchFieldException(c.getName() + "." + name);
    }

    private static synchronized void init(Object vehicle) throws Exception {
        if (mScriptName != null) {
            return;
        }
        ClassLoader cl = vehicle.getClass().getClassLoader();
        Class<?> bv = Class.forName("zombie.vehicles.BaseVehicle", false, cl);
        Class<?> sprite = Class.forName("zombie.iso.sprite.IsoSprite", false, cl);
        Class<?> slot = Class.forName("zombie.core.skinnedmodel.ModelManager$ModelSlot", false, cl);
        Class<?> inst = Class.forName("zombie.core.skinnedmodel.model.ModelInstance", false, cl);
        Class<?> model = Class.forName("zombie.core.skinnedmodel.model.Model", false, cl);
        Class<?> mesh = Class.forName("zombie.core.skinnedmodel.model.ModelMesh", false, cl);
        Class<?> vbo = Class.forName("zombie.core.skinnedmodel.model.VertexBufferObject", false, cl);
        Class<?> fmt = Class.forName("zombie.core.skinnedmodel.model.VertexBufferObject$VertexFormat", false, cl);
        Class<?> el = Class.forName("zombie.core.skinnedmodel.model.VertexBufferObject$VertexElement", false, cl);
        Class<?> shader = Class.forName("zombie.core.skinnedmodel.shader.Shader", false, cl);
        Class<?> anim = Class.forName("zombie.core.skinnedmodel.animation.AnimationPlayer", false, cl);
        cVehicleInstance = Class.forName("zombie.core.skinnedmodel.model.VehicleModelInstance", false, cl);
        fSprite = field(bv, "sprite");
        fSlot = field(sprite, "modelSlot");
        fSlotActive = field(slot, "active");
        fSlotModel = field(slot, "model");
        fSlotSub = field(slot, "sub");
        fSlotMuzzle = field(slot, "muzzleFlashModels");
        fInstModel = field(inst, "model");
        fInstParent = field(inst, "parent");
        fInstAnim = field(inst, "animPlayer");
        fModelName = field(model, "name");
        fModelMesh = field(model, "mesh");
        fModelEffect = field(model, "effect");
        fModelTag = field(model, "tag");
        fModelStatic = field(model, "isStatic");
        fMeshVb = field(mesh, "vb");
        fVbFormat = field(vbo, "vertexFormat");
        fElType = field(el, "type");
        fModelTransforms = field(anim, "modelTransforms");
        mMeshReady = mesh.getMethod("isReady");
        mFmtCount = fmt.getMethod("getNumElements");
        mFmtElement = fmt.getMethod("getElement", int.class);
        mShaderName = shader.getMethod("getName");
        mVehicleShader = shader.getMethod("isVehicleShader");
        mAnimReady = anim.getMethod("isReady");
        try {
            mNumBones = Class.forName("zombie.core.skinnedmodel.model.SkinningData", false, cl).getMethod("numBones");
        } catch (Throwable t) {
            mNumBones = null;
        }
        fServer = Class.forName("zombie.network.GameServer", false, cl).getField("server");
        mScriptName = bv.getMethod("getScriptName");
        Log.debug("[LabVehiclePhysics] draw check ready: every " + (PERIOD_NS / 1_000_000_000L)
                + " s the models of tracked vehicles (and of any vehicle with a suspicious model) are listed");
    }
}
