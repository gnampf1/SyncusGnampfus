package de.gnampf.syncusgnampfus;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.List;

import de.willuhn.jameica.hbci.SynchronizeOptions;
import de.willuhn.jameica.hbci.rmi.Konto;
import de.willuhn.jameica.hbci.synchronize.AbstractSynchronizeBackend;
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
		Class<SyncusGnampfusSynchronizeJobKontoauszug> type = SyncusGnampfusSynchronizeJobKontoauszug.class;

		var jobHash = new Hashtable<String, ArrayList<SyncusGnampfusSynchronizeJobKontoauszug>>(); 
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
				
				SyncusGnampfusSynchronizeJobKontoauszug job = (SyncusGnampfusSynchronizeJobKontoauszug) getBackend().create(type,kt); // erzeugt eine Instanz von ExampleSynchronizeJobKontoauszug
				job.setContext(SynchronizeJob.CTX_ENTITY,kt);

				var username = kt.getKundennummer();
				var list = jobHash.getOrDefault(username, new ArrayList<SyncusGnampfusSynchronizeJobKontoauszug>());
				list.add(job);
				jobHash.putIfAbsent(username, list);
			}
			catch (Exception e)
			{
				Logger.error("unable to load synchronize jobs",e);
			}
		}

		for (var item : jobHash.entrySet())
		{
			var list = item.getValue();
			for (int i = 0; i < list.size(); i++)
			{
				list.get(i).setSkipLogout(i < list.size() - 1);
			}
			jobs.addAll(list);
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