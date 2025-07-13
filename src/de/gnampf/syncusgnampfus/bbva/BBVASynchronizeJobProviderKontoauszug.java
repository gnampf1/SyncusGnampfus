package de.gnampf.syncusgnampfus.bbva;

import java.rmi.RemoteException;
import java.util.ArrayList;
import javax.annotation.Resource;

import de.gnampf.syncusgnampfus.SyncusGnampfusSynchronizeJobProviderKontoauszug;
import de.willuhn.jameica.hbci.rmi.Konto;
import de.willuhn.jameica.hbci.synchronize.AbstractSynchronizeBackend;
import de.willuhn.jameica.hbci.synchronize.jobs.SynchronizeJob;

public class BBVASynchronizeJobProviderKontoauszug extends SyncusGnampfusSynchronizeJobProviderKontoauszug 
{
	@Resource
	private BBVASynchronizeBackend backend = null;

	@Override
	protected AbstractSynchronizeBackend<?> getBackend() { return backend; }

	public BBVASynchronizeJobProviderKontoauszug()
	{
		JOBS = new ArrayList<Class<? extends SynchronizeJob>>()
		{{
			add(BBVASynchronizeJobKontoauszug.class);
		}};
	}

    @Override
    public boolean supports(Class type, Konto konto) 
    {
    	try 
    	{
    		return konto.getBLZ().equals("50031900");
    	}
    	catch (RemoteException e)
    	{
    		return false;
    	}
    }
}
