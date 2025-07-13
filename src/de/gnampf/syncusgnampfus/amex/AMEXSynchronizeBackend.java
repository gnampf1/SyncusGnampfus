package de.gnampf.syncusgnampfus.amex;

import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;
import de.gnampf.syncusgnampfus.SyncusGnampfusSynchronizeBackend;
import de.willuhn.annotation.Lifecycle;
import de.willuhn.annotation.Lifecycle.Type;
import de.willuhn.jameica.hbci.rmi.Konto;

@Lifecycle(Type.CONTEXT)
public class AMEXSynchronizeBackend extends SyncusGnampfusSynchronizeBackend
{
	public final static String META_INHALTSVERGLEICH = "Inhalte statt TransaktionsId vergleichen (Daten\u00FCbernahme von Mashup)";
	public final static String META_OTPTYPE = "OTP-Reihenfolge, E=EMAIL, S=SMS, A=App";

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
		try 
		{
			if (konto == null || !konto.getBackendClass().equals(this.getClass().getName()))
			{
				return null;
			}
			if (konto.getMeta(META_OTPTYPE, null) == null)
			{
				konto.setMeta(META_OTPTYPE, "ESA");
			}
			if (konto.getMeta(META_INHALTSVERGLEICH, null) == null)
			{
				konto.setMeta(META_INHALTSVERGLEICH, "true");
			}

			List<String> result = new ArrayList<String>();
			result.add(META_OTPTYPE);
			result.add(META_INHALTSVERGLEICH + "(true/false)");
			return result;
		} 
		catch (RemoteException e) 
		{
			return null;
		}
    }
}
