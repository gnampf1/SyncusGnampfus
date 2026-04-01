package de.gnampf.syncusgnampfus.scalablecapital;

import java.util.Arrays;
import java.util.List;

import de.gnampf.syncusgnampfus.SyncusGnampfusSynchronizeBackend;
import de.willuhn.annotation.Lifecycle;
import de.willuhn.annotation.Lifecycle.Type;
import de.willuhn.jameica.hbci.rmi.Konto;

@Lifecycle(Type.CONTEXT)
public class ScalablecapitalSynchronizeBackend extends SyncusGnampfusSynchronizeBackend
{
    static String JSONDATA = "Speichere JSON-Daten";
    static String JSONDATAPROP = JSONDATA + " (true/false)";

	@Override
    public String getName()
    {
        return "Scalable Capital";
    }


	/**
	 * @see de.willuhn.jameica.hbci.synchronize.AbstractSynchronizeBackend#getPropertyNames(de.willuhn.jameica.hbci.rmi.Konto)
	 */
	@Override
	public List<String> getPropertyNames(Konto konto)
	{
    	return Arrays.asList(JSONDATAPROP);
	}

}
