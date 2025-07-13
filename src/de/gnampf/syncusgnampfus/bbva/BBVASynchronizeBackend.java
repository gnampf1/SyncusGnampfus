package de.gnampf.syncusgnampfus.bbva;

import de.gnampf.syncusgnampfus.SyncusGnampfusSynchronizeBackend;
import de.willuhn.annotation.Lifecycle;
import de.willuhn.annotation.Lifecycle.Type;

@Lifecycle(Type.CONTEXT)
public class BBVASynchronizeBackend extends SyncusGnampfusSynchronizeBackend
{
    @Override
    public String getName()
    {
        return "BBVA";
    }
}
