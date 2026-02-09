package de.gnampf.syncusgnampfus.bbva;

import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;

import de.gnampf.syncusgnampfus.SyncusGnampfusSynchronizeBackend;
import de.willuhn.annotation.Lifecycle;
import de.willuhn.annotation.Lifecycle.Type;
import de.willuhn.jameica.hbci.rmi.Konto;

@Lifecycle(Type.CONTEXT)
public class BBVASynchronizeBackend extends SyncusGnampfusSynchronizeBackend
{
	public final static String META_HEADERS = "Headerdaten";
	public final static String META_URL = "URL";

	@Override
    public String getName()
    {
        return "BBVA";
    }
    /**
     * @see de.willuhn.jameica.hbci.synchronize.AbstractSynchronizeBackend#getPropertyNames(de.willuhn.jameica.hbci.rmi.Konto)
     */
    @Override
    public List<String> getPropertyNames(Konto konto)
    {
		try 
		{
			if (konto == null || !konto.getBackendClass().equals(this.getClass().getName()))
			{
				return null;
			}

			List<String> result = new ArrayList<String>();
			return result;
		} 
		catch (RemoteException e) 
		{
			return null;
		}
    }
}
