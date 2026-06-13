package nl.whitemcwizard.universalsettings.mixin;

import net.minecraft.client.multiplayer.ServerList;
import nl.whitemcwizard.universalsettings.sync.SyncManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerList.class)
public class ServerListMixin {

    @Inject(method = "save", at = @At("TAIL"))
    private void universalsettings$onSave(CallbackInfo ci) {
        SyncManager.get().onServersSaved();
    }
}
