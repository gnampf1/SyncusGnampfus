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
	public final static String META_CHROMEPATH = "Pfad zu chrome.exe (optional bei Problemen)";
	public final static String META_FIREFOXPATH = "Pfad zu firefox.exe (optional bei Problemen)";
	public final static String META_NOTHEADLESS = "Browser beim Ermitteln Akamai-Header anzeigen";
	public final static String META_HEADERS = "Headerdaten";

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
			result.add(META_FIREFOXPATH);
			result.add(META_NOTHEADLESS + "(true/false)");
			return result;
		} 
		catch (RemoteException e) 
		{
			return null;
		}
    }
}
