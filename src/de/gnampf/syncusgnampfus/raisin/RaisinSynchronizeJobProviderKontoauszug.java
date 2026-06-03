package de.gnampf.syncusgnampfus.raisin;

import java.rmi.RemoteException;
import java.util.ArrayList;
import javax.annotation.Resource;

import de.gnampf.syncusgnampfus.SyncusGnampfusSynchronizeJobProviderKontoauszug;
import de.willuhn.jameica.hbci.rmi.Konto;
import de.willuhn.jameica.hbci.synchronize.AbstractSynchronizeBackend;
import de.willuhn.jameica.hbci.synchronize.jobs.SynchronizeJob;

public class RaisinSynchronizeJobProviderKontoauszug extends SyncusGnampfusSynchronizeJobProviderKontoauszug {

	@Resource
	private RaisinSynchronizeBackend backend = null;

	@Override
	protected AbstractSynchronizeBackend<?> getBackend() { return backend; }

	public RaisinSynchronizeJobProviderKontoauszug()
	{
		JOBS = new ArrayList<Class<? extends SynchronizeJob>>()
		{{
			add(RaisinSynchronizeJobKontoauszug.class);
		}};
	}

    @Override
    public boolean supports(Class type, Konto konto) 
    {
    	try 
    	{
			return RaisinSynchronizeBackend.class.getName().equals(konto.getBackendClass());
		} 
    	catch (RemoteException e) 
    	{
        	return false;
    	}
    }
}
