# Patched game methods

Every place where the mods hook into the game's Java code, generated from the `@Patch`
annotations in `mods/*/src`. Each patch is a ByteBuddy advice applied by ZombieBuddy at
load time; game files on disk are never modified.

| Mod | Game class | Method | Patch source |
|---|---|---|---|
| LabVehiclePhysics | `zombie.characters.animals.IsoAnimal` | `Hit` | [Patch_animalHit.java](../mods/LabVehiclePhysics/src/pz/labvehicle/Patch_animalHit.java) |
| LabVehiclePhysics | `zombie.network.fields.hit.VehicleHitField` | `process` | [Patch_animalHitServer.java](../mods/LabVehiclePhysics/src/pz/labvehicle/Patch_animalHitServer.java) |
| LabVehiclePhysics | `zombie.vehicles.BaseVehicle` | `applyImpulseFromHitObject` | [Patch_animalImpulse.java](../mods/LabVehiclePhysics/src/pz/labvehicle/Patch_animalImpulse.java) |
| LabVehiclePhysics | `zombie.characters.animals.IsoAnimal` | `update` | [Patch_animalThrow.java](../mods/LabVehiclePhysics/src/pz/labvehicle/Patch_animalThrow.java) |
| LabVehiclePhysics | `zombie.characters.IsoGameCharacter` | `becomeCorpse` | [Patch_catchCorpse.java](../mods/LabVehiclePhysics/src/pz/labvehicle/Patch_catchCorpse.java) |
| LabVehiclePhysics | `zombie.network.CoopMaster` | `getGarbageCollector` | [Patch_coopServerAgent.java](../mods/LabVehiclePhysics/src/pz/labvehicle/Patch_coopServerAgent.java) |
| LabVehiclePhysics | `zombie.network.CoopMaster` | `launchServer` | [Patch_coopServerName.java](../mods/LabVehiclePhysics/src/pz/labvehicle/Patch_coopServerName.java) |
| LabVehiclePhysics | `zombie.core.physics.RagdollController` | `postUpdate` | [Patch_corpseFollowsRagdoll.java](../mods/LabVehiclePhysics/src/pz/labvehicle/Patch_corpseFollowsRagdoll.java) |
| LabVehiclePhysics | `zombie.network.packets.character.DeadCharacterPacket` | `processClient` | [Patch_corpsePlacement.java](../mods/LabVehiclePhysics/src/pz/labvehicle/Patch_corpsePlacement.java) |
| LabVehiclePhysics | `zombie.characters.IsoGameCharacter` | `onHitByVehicleApplyDamage` | [Patch_crushDamage.java](../mods/LabVehiclePhysics/src/pz/labvehicle/Patch_crushDamage.java) |
| LabVehiclePhysics | `zombie.characters.IsoGameCharacter` | `die` | [Patch_deferCorpse.java](../mods/LabVehiclePhysics/src/pz/labvehicle/Patch_deferCorpse.java) |
| LabVehiclePhysics | `zombie.core.physics.CarController` | `checkTire` | [Patch_enginePower.java](../mods/LabVehiclePhysics/src/pz/labvehicle/Patch_enginePower.java) |
| LabVehiclePhysics | `zombie.characters.IsoGameCharacter` | `getMass` | [Patch_getMass.java](../mods/LabVehiclePhysics/src/pz/labvehicle/Patch_getMass.java) |
| LabVehiclePhysics | `zombie.vehicles.BaseVehicle` | `applyImpulseFromHitPedestrian` | [Patch_impulseBudget.java](../mods/LabVehiclePhysics/src/pz/labvehicle/Patch_impulseBudget.java) |
| LabVehiclePhysics | `zombie.network.packets.vehicle.VehiclePhysicsPacket` | `processServer` | [Patch_nanPacketIn.java](../mods/LabVehiclePhysics/src/pz/labvehicle/Patch_nanPacketIn.java) |
| LabVehiclePhysics | `zombie.network.packets.vehicle.VehiclePhysicsPacket` | `set` | [Patch_nanPacketOut.java](../mods/LabVehiclePhysics/src/pz/labvehicle/Patch_nanPacketOut.java) |
| LabVehiclePhysics | `zombie.characters.IsoPlayer` | `update` | [Patch_nanPlayer.java](../mods/LabVehiclePhysics/src/pz/labvehicle/Patch_nanPlayer.java) |
| LabVehiclePhysics | `zombie.iso.IsoMovingObject` | `setX` | [Patch_nanWriteX.java](../mods/LabVehiclePhysics/src/pz/labvehicle/Patch_nanWriteX.java) |
| LabVehiclePhysics | `zombie.iso.IsoMovingObject` | `setY` | [Patch_nanWriteY.java](../mods/LabVehiclePhysics/src/pz/labvehicle/Patch_nanWriteY.java) |
| LabVehiclePhysics | `zombie.characters.IsoGameCharacter` | `onHitByVehicle` | [Patch_onHitByVehicle.java](../mods/LabVehiclePhysics/src/pz/labvehicle/Patch_onHitByVehicle.java) |
| LabVehiclePhysics | `zombie.vehicles.BaseVehicle` | `setModelVisible` | [Patch_placeholderModels.java](../mods/LabVehiclePhysics/src/pz/labvehicle/Patch_placeholderModels.java) |
| LabVehiclePhysics | `zombie.vehicles.BaseVehicle` | `applyImpulseFromHitPlant` | [Patch_plantImpulse.java](../mods/LabVehiclePhysics/src/pz/labvehicle/Patch_plantImpulse.java) |
| LabVehiclePhysics | `zombie.vehicles.BaseVehicle` | `testCollisionWithProneCharacter` | [Patch_proneImpulse.java](../mods/LabVehiclePhysics/src/pz/labvehicle/Patch_proneImpulse.java) |
| LabVehiclePhysics | `zombie.characters.IsoZombie` | `postHitByVehicleUpdateStance` | [Patch_pushKnockdown.java](../mods/LabVehiclePhysics/src/pz/labvehicle/Patch_pushKnockdown.java) |
| LabVehiclePhysics | `zombie.characters.NetworkZombieAI` | `parse` | [Patch_remoteRagdollUpdate.java](../mods/LabVehiclePhysics/src/pz/labvehicle/Patch_remoteRagdollUpdate.java) |
| LabVehiclePhysics | `zombie.vehicles.BaseVehicle` | `isCollided` | [Patch_remoteVehicleContact.java](../mods/LabVehiclePhysics/src/pz/labvehicle/Patch_remoteVehicleContact.java) |
| LabVehiclePhysics | `zombie.vehicles.VehiclePart` | `getContainerCapacity` | [Patch_tankCapacity.java](../mods/LabVehiclePhysics/src/pz/labvehicle/Patch_tankCapacity.java) |
| LabVehiclePhysics | `zombie.inventory.InventoryItem` | `getMaxCapacity` | [Patch_tankItemCapacity.java](../mods/LabVehiclePhysics/src/pz/labvehicle/Patch_tankItemCapacity.java) |
| LabVehiclePhysics | `zombie.vehicles.VehiclePart` | `setContainerContentAmount` | [Patch_tankWrite.java](../mods/LabVehiclePhysics/src/pz/labvehicle/Patch_tankWrite.java) |
| LabVehiclePhysics | `zombie.vehicles.BaseVehicle` | `damageObjects` | [Patch_treeCrash.java](../mods/LabVehiclePhysics/src/pz/labvehicle/Patch_treeCrash.java) |
| LabVehiclePhysics | `zombie.vehicles.BaseVehicle` | `crash` | [Patch_treeCrashDamage.java](../mods/LabVehiclePhysics/src/pz/labvehicle/Patch_treeCrashDamage.java) |
| LabVehiclePhysics | `zombie.vehicles.BaseVehicle` | `breakingObjects` | [Patch_vegetationCap.java](../mods/LabVehiclePhysics/src/pz/labvehicle/Patch_vegetationCap.java) |
| LabVehiclePhysics | `zombie.vehicles.BaseVehicle` | `update` | [Patch_vehicleFloor.java](../mods/LabVehiclePhysics/src/pz/labvehicle/Patch_vehicleFloor.java) |
| LabVehiclePhysics | `zombie.vehicles.BaseVehicle` | `getFudgedMass` | [Patch_vehicleLiveMass.java](../mods/LabVehiclePhysics/src/pz/labvehicle/Patch_vehicleLiveMass.java) |
| LabVehiclePhysics | `zombie.scripting.objects.VehicleScript` | `Loaded` | [Patch_vehicleMass.java](../mods/LabVehiclePhysics/src/pz/labvehicle/Patch_vehicleMass.java) |
| LabVehiclePhysics | `zombie.vehicles.BaseVehicle` | `createPhysics` | [Patch_vehiclePhysicsInit.java](../mods/LabVehiclePhysics/src/pz/labvehicle/Patch_vehiclePhysicsInit.java) |
| LabVehiclePhysics | `zombie.ai.states.ZombieOnGroundState` | `enter` | [Patch_zombieLanded.java](../mods/LabVehiclePhysics/src/pz/labvehicle/Patch_zombieLanded.java) |
| LabVehiclePhysics | `zombie.popman.NetworkZombieManager` | `updateAuth` | [Patch_zombieOwnerHold.java](../mods/LabVehiclePhysics/src/pz/labvehicle/Patch_zombieOwnerHold.java) |
| LabRagdollMP | `zombie.core.Core` | `getOptionUsePhysicsHitReaction` | [Patch_Core_usePhysicsHitReaction.java](../mods/LabRagdollMP/src/pz/labragdoll/Patch_Core_usePhysicsHitReaction.java) |
| LabRagdollMP | `zombie.characters.IsoGameCharacter` | `canRagdoll` | [Patch_IsoGameCharacter_canRagdoll.java](../mods/LabRagdollMP/src/pz/labragdoll/Patch_IsoGameCharacter_canRagdoll.java) |
| LabRagdollMP | `zombie.network.CoopMaster` | `getGarbageCollector` | [Patch_coopServerAgent.java](../mods/LabRagdollMP/src/pz/labragdoll/Patch_coopServerAgent.java) |
| LabRagdollMP | `zombie.network.CoopMaster` | `launchServer` | [Patch_coopServerName.java](../mods/LabRagdollMP/src/pz/labragdoll/Patch_coopServerName.java) |
| LabRagdollMP | `zombie.characters.IsoGameCharacter` | `canUseCurrentPoseForCorpse` | [Patch_corpsePose.java](../mods/LabRagdollMP/src/pz/labragdoll/Patch_corpsePose.java) |

Besides these, `NativePatch.java` lifts the hidden 2,400 kg suspension limit of the game's
own physics library (`PZBullet64.dll`) **in memory** through the Java Foreign Function &
Memory API. It changes exactly one number, Bullet's maximum suspension force (6000), which it
locates by the six `btVehicleTuning` constants stored next to each other; if they are not found
(for example after a game update) nothing is written. The page protection is restored right
after the write, and the DLL file on disk is never touched.
