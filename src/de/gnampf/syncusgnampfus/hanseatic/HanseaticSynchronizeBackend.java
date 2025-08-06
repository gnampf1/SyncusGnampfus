package de.gnampf.syncusgnampfus.hanseatic;

import de.gnampf.syncusgnampfus.SyncusGnampfusSynchronizeBackend;
import de.willuhn.annotation.Lifecycle;
import de.willuhn.annotation.Lifecycle.Type;

@Lifecycle(Type.CONTEXT)
public class HanseaticSynchronizeBackend extends SyncusGnampfusSynchronizeBackend
{
	public final static String META_DEVICETOKEN = "DeviceToken";

	@Override
    public String getName()
    {
        return "HanseaticBank";
    }
}
