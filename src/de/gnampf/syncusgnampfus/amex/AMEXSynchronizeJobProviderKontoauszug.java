package de.gnampf.syncusgnampfus.amex;

import java.rmi.RemoteException;
import java.util.ArrayList;
import javax.annotation.Resource;

import de.gnampf.syncusgnampfus.SyncusGnampfusSynchronizeJobProviderKontoauszug;
import de.willuhn.jameica.hbci.rmi.Konto;
import de.willuhn.jameica.hbci.synchronize.AbstractSynchronizeBackend;
import de.willuhn.jameica.hbci.synchronize.jobs.SynchronizeJob;

public class AMEXSynchronizeJobProviderKontoauszug extends SyncusGnampfusSynchronizeJobProviderKontoauszug 
{
	@Resource
	private AMEXSynchronizeBackend backend = null;

	@Override
	protected AbstractSynchronizeBackend<?> getBackend() { return backend; }

	public AMEXSynchronizeJobProviderKontoauszug()
	{
		JOBS = new ArrayList<Class<? extends SynchronizeJob>>()
		{{
			add(AMEXSynchronizeJobKontoauszug.class);
		}};
	}

    @Override
    public boolean supports(Class type, Konto konto) 
    {
    	try 
    	{
			return AMEXSynchronizeBackend.class.getName().equals(konto.getBackendClass());
		} 
    	catch (RemoteException e) 
    	{
        	return false;
    	}
    }
}
