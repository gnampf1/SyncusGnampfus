package de.gnampf.syncusgnampfus.hanseatic;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Resource;

import de.gnampf.syncusgnampfus.SyncusGnampfusSynchronizeJobProvider;
import de.gnampf.syncusgnampfus.SyncusGnampfusSynchronizeBackend;
import de.gnampf.syncusgnampfus.SyncusGnampfusSynchronizeJob;
import de.willuhn.annotation.Lifecycle;
import de.willuhn.annotation.Lifecycle.Type;
import de.willuhn.jameica.hbci.rmi.Konto;
import de.willuhn.jameica.hbci.synchronize.AbstractSynchronizeBackend;
import de.willuhn.jameica.hbci.synchronize.SynchronizeBackend;
import de.willuhn.jameica.hbci.synchronize.SynchronizeEngine;
import de.willuhn.jameica.hbci.synchronize.SynchronizeJobProvider;
import de.willuhn.logging.Logger;
import de.willuhn.util.ProgressMonitor;

@Lifecycle(Type.CONTEXT)
public class HanseaticSynchronizeBackend extends SyncusGnampfusSynchronizeBackend
{
    @Override
    public String getName()
    {
        return "HanseaticBank";
    }
}
