package de.gnampf.syncusgnampfus.hanseatic;

import java.rmi.RemoteException;
import java.util.ArrayList;
import javax.annotation.Resource;

import de.gnampf.syncusgnampfus.SyncusGnampfusSynchronizeJobProviderKontoauszug;
import de.willuhn.jameica.hbci.rmi.Konto;
import de.willuhn.jameica.hbci.synchronize.AbstractSynchronizeBackend;
import de.willuhn.jameica.hbci.synchronize.jobs.SynchronizeJob;

public class HanseaticSynchronizeJobProviderKontoauszug extends SyncusGnampfusSynchronizeJobProviderKontoauszug 
{
	@Resource
	private HanseaticSynchronizeBackend backend = null;

	@Override
	protected AbstractSynchronizeBackend<?> getBackend() { return backend; }

	public HanseaticSynchronizeJobProviderKontoauszug()
	{
		JOBS = new ArrayList<Class<? extends SynchronizeJob>>()
		{{
			add(HanseaticSynchronizeJobKontoauszug.class);
		}};
	}

	@Override
	public boolean supports(Class type, Konto k) 
	{
		try
		{
			return k.getBLZ().equals("20120700");
		} 
		catch (RemoteException e) 
		{
			return false;
		}
	}
}
