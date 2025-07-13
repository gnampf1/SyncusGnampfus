package de.gnampf.syncusgnampfus.amex;

import java.util.List;
import de.gnampf.syncusgnampfus.SyncusGnampfusSynchronizeBackend;
import de.willuhn.annotation.Lifecycle;
import de.willuhn.annotation.Lifecycle.Type;
import de.willuhn.jameica.hbci.rmi.Konto;

@Lifecycle(Type.CONTEXT)
public class AMEXSynchronizeBackend extends SyncusGnampfusSynchronizeBackend
{
    @Override
    public String getName()
    {
        return "AMEX";
    }

    /**
     * @see de.willuhn.jameica.hbci.synchronize.AbstractSynchronizeBackend#getPropertyNames(de.willuhn.jameica.hbci.rmi.Konto)
     */
    @Override
    public List<String> getPropertyNames(Konto konto)
    {
    	return null;
    }
}
