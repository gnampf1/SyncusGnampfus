package de.gnampf.syncusgnampfus;

import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import javax.annotation.Resource;

import de.gnampf.syncusgnampfus.amex.AMEXSynchronizeJobKontoauszug;
import de.gnampf.syncusgnampfus.bbva.BBVASynchronizeJobKontoauszug;
import de.gnampf.syncusgnampfus.hanseatic.HanseaticSynchronizeBackend;
import de.gnampf.syncusgnampfus.hanseatic.HanseaticSynchronizeJobKontoauszug;
import de.willuhn.jameica.hbci.SynchronizeOptions;
import de.willuhn.jameica.hbci.rmi.Konto;
import de.willuhn.jameica.hbci.synchronize.AbstractSynchronizeBackend;
import de.willuhn.jameica.hbci.synchronize.SynchronizeBackend;
import de.willuhn.jameica.hbci.synchronize.jobs.SynchronizeJob;
import de.willuhn.jameica.hbci.synchronize.jobs.SynchronizeJobKontoauszug;
import de.willuhn.logging.Logger;

public abstract class SyncusGnampfusSynchronizeJobProviderKontoauszug implements SyncusGnampfusSynchronizeJobProvider
{
	protected abstract AbstractSynchronizeBackend getBackend();
	
	protected List<Class<? extends SynchronizeJob>> JOBS;

	/**
	 * @see de.willuhn.jameica.hbci.synchronize.SynchronizeJobProvider#getSynchronizeJobs(de.willuhn.jameica.hbci.rmi.Konto)
	 */
	@Override
	public List<SynchronizeJob> getSynchronizeJobs(Konto k)
	{
		Class<SynchronizeJobKontoauszug> type = SynchronizeJobKontoauszug.class;

		List<SynchronizeJob> jobs = new LinkedList<SynchronizeJob>();
		for (Konto kt:(List<Konto>)getBackend().getSynchronizeKonten(k))
		{
			try
			{
				if (!getBackend().supports(type,k)) // Checken, ob das ein passendes Konto ist
					continue;

				final SynchronizeOptions options = new SynchronizeOptions(kt);

				if (!options.getSyncKontoauszuege()) // Sync-Option zum Kontoauszugs-Abruf aktiv?
					continue;

				SynchronizeJobKontoauszug job = (SynchronizeJobKontoauszug) getBackend().create(type,kt); // erzeugt eine Instanz von ExampleSynchronizeJobKontoauszug
				job.setContext(SynchronizeJob.CTX_ENTITY,kt);
				jobs.add(job);
			}
			catch (Exception e)
			{
				Logger.error("unable to load synchronize jobs",e);
			}
		}

		return jobs;
	}

	@Override
	public List<Class<? extends SynchronizeJob>> getJobTypes()
	{
		return JOBS;
	}

	@Override
	public int compareTo(Object o)
	{
		return 1;
	}
}