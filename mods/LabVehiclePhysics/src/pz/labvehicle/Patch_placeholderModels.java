package pz.labvehicle;

import java.lang.reflect.Field;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Placeholder models are not added to vehicles, so 3D renderers do not drop the whole vehicle.
 *
 * Papa_Chad's tracked vehicles (Military Tool Kit: M60A3, M41, M113, M548, M163) give each of
 * their four physics wheels the model fake_wheel: one vertex, no triangles, scale 0.01, no texture
 * — an invisible stand-in, since the tracks are drawn by the hull and track models. The game loads
 * such a mesh but builds no vertex buffer for it (ModelMesh.vb stays null) and the isometric
 * renderer simply draws nothing. A 3D renderer that requires every model of a vehicle to be
 * drawable (Viewpoint does) drops the whole tank instead. The models appear only on wheels with a
 * tyre installed, and Military Tool Kit installs tyres on a tank in use, which is why a parked tank
 * was visible and the one being driven was not (seen in the lab with VehicleDrawCheck, 03.10.2026).
 *
 * BaseVehicle.setModelVisible(part, scriptModel, true) is skipped for a script model whose file is
 * one of PLACEHOLDERS: the model never enters BaseVehicle.models. Nothing visible changes, the
 * physics wheels come from the vehicle script, and a missing wheel model only means the wheel is
 * taken as on the ground by the run-over test. The method returns null, as vanilla does for a part
 * without a model; the multiplayer sync of part models (VehiclePartModels) accepts that.
 */
@Patch(className = "zombie.vehicles.BaseVehicle", methodName = "setModelVisible", warmUp = true)
public class Patch_placeholderModels {

    @Patch.OnEnter(skipOn = true)
    public static boolean enter(@Patch.Argument(1) Object scriptModel, @Patch.Argument(2) boolean visible) {
        return visible && Impl.isPlaceholder(scriptModel);
    }

    public static final class Impl {
        /** Model script names (VehicleScript.Model.file) of invisible stand-in models without geometry. */
        public static final String[] PLACEHOLDERS = {"fake_wheel"};

        public static volatile boolean broken = false;
        public static Field fFile;
        public static long skipped = 0L;
        public static boolean logged = false;

        public static boolean isPlaceholder(Object scriptModel) {
            if (broken || scriptModel == null || !LabGate.active()) {
                return false;
            }
            try {
                if (fFile == null) {
                    fFile = scriptModel.getClass().getField("file");
                }
                Object file = fFile.get(scriptModel);
                if (!(file instanceof String)) {
                    return false;
                }
                String name = VehicleCfg.bareName((String) file);
                for (String p : PLACEHOLDERS) {
                    if (p.equalsIgnoreCase(name)) {
                        skipped++;
                        if (!logged) {
                            logged = true;
                            Log.info("[LabVehiclePhysics] placeholder model " + name + " is not added to vehicles: it has no "
                                    + "geometry, and a 3D renderer that needs every model drawable would drop the whole vehicle");
                        }
                        return true;
                    }
                }
                return false;
            } catch (Throwable t) {
                broken = true;
                Log.info("[LabVehiclePhysics] ERROR in the placeholder-model patch, disabling it: " + t);
                return false;
            }
        }
    }
}
