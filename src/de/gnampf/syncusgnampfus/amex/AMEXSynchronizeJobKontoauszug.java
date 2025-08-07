package de.gnampf.syncusgnampfus.amex;

import java.net.URLEncoder;
import java.rmi.RemoteException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Map;
import javax.annotation.Resource;

import org.htmlunit.HttpMethod;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.Cookie;
import org.json.JSONArray;
import org.json.JSONObject;

import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator.WaitForOptions;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitForSelectorState;

import de.gnampf.syncusgnampfus.KeyValue;
import de.gnampf.syncusgnampfus.SyncusGnampfusSynchronizeJob;
import de.gnampf.syncusgnampfus.SyncusGnampfusSynchronizeJobKontoauszug;
import de.gnampf.syncusgnampfus.WebResult;
import de.willuhn.datasource.rmi.DBIterator;
import de.willuhn.jameica.hbci.Settings;
import de.willuhn.jameica.hbci.messaging.ObjectChangedMessage;
import de.willuhn.jameica.hbci.messaging.SaldoMessage;
import de.willuhn.jameica.hbci.rmi.Konto;
import de.willuhn.jameica.hbci.rmi.Umsatz;
import de.willuhn.jameica.hbci.synchronize.SynchronizeBackend;
import de.willuhn.jameica.system.Application;
import de.willuhn.logging.Level;
import de.willuhn.util.ApplicationException;
import io.github.kihdev.playwright.stealth4j.Stealth4j;


public class AMEXSynchronizeJobKontoauszug extends SyncusGnampfusSynchronizeJobKontoauszug implements SyncusGnampfusSynchronizeJob 
{
	private static SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");

	@Resource
	private AMEXSynchronizeBackend backend = null;

	@Override
	protected SynchronizeBackend getBackend() { return backend; }

	private AMEXRequestInterceptor GetCorrelationId(Konto konto, String user, String passwort, int retry) throws Exception 
	{
		log(Level.INFO, "Ermittlung CorrelationId gestartet");
		var interceptor = new AMEXRequestInterceptor();
		try (Playwright playwright = Playwright.create()) 
		{
			var headless = "false".equals(konto.getMeta(AMEXSynchronizeBackend.META_NOTHEADLESS,  "false"));
			var options1 = new BrowserType.LaunchOptions().setSlowMo(100 * retry).setHeadless(headless);
			var browser = playwright.chromium().launch(options1);

			var stealthContext = Stealth4j.newStealthContext(browser);
			var pwPage = stealthContext.newPage();
			pwPage.route("**/*", t -> 
			{
				if (t.request().url().endsWith("/myca/logon/emea/action/login") && "POST".equals(t.request().method()))
				{
					interceptor.log += "Triggered\n";
					try 
					{
						interceptor.Header.clear();
						t.request().headersArray().forEach(h -> 
						{
							var key = h.name.toLowerCase().trim();
							if (!"content-type".equals(key) &&
									!"host".equals(key) &&
									!"cookie".equals(key))
							{
								interceptor.Header.add(new KeyValue<>(h.name, h.value));
								if (h.name.equals("one-data-correlation-id"))
								{
									interceptor.Body = t.request().postData();
									interceptor.Url = t.request().url();
									interceptor.log += "Detected\n";
								}
							}
						});

					}
					catch (Exception e) 
					{ 
						interceptor.log +="EX: " + e+"\n";
					}					
				}

				if (interceptor.Url != null)
				{
					t.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
							.setStatus(200)
							.setBody("accept"));
				}
				else 
				{
					t.resume(new com.microsoft.playwright.Route.ResumeOptions());
				}
			});
			pwPage.navigate("https://www.americanexpress.com/de-de/account/login?DestPage=https%3A%2F%2Fglobal.americanexpress.com%2Factivity%2Frecent%3Fappv5%3Dfalse");
			var cookieBanner = pwPage.getByTestId("granular-banner-button-accept-all");
			if (cookieBanner != null)
			{
				try 
				{
					cookieBanner.waitFor(new WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(5000));
					if (cookieBanner.count() > 0) 
					{
						cookieBanner.click();
					}
				} 
				catch (com.microsoft.playwright.TimeoutError e) { }
			}
			pwPage.getByTestId("userid-input").fill("BlahIdBlah");
			pwPage.getByTestId("password-input").fill("BlahWortBlah");
			pwPage.getByTestId("submit-button").click();

			int timeout = 600;
			while (interceptor.Url == null && timeout > 0)
			{
				pwPage.screenshot();
				Thread.sleep(100);
				timeout--;
			}

			if (timeout == 0)
			{
				interceptor.Url = null;
				throw new ApplicationException("Timeout beim Ermitteln der CorrelationId");
			}

			var cookieManager = webClient.getCookieManager();
			cookieManager.clearCookies();
			for (var c : pwPage.context().cookies())
			{
				cookieManager.addCookie(new Cookie(c.domain, c.name, c.value, c.path, new Date(c.expires.longValue()*1000), c.secure, c.httpOnly, c.sameSite.toString()));
			}

			pwPage.close();
			browser.close();
		}
		catch (Exception e)
		{
			log(Level.ERROR, "Kann CorrelationId nicht ermitteln: "+e);
			log(Level.ERROR, "Meldungen vom Interceptor: " + interceptor.log);
			throw e;
		}

		permanentHeaders.clear();
		permanentHeaders.addAll(interceptor.Header);
		return interceptor;
	}
	
	private void addDeviceCookies(Konto konto) throws RemoteException
	{
		var cookieManager = webClient.getCookieManager();
		var cookiesJSON = new JSONArray(konto.getMeta(AMEXSynchronizeBackend.META_DEVICECOOKIES, "[]"));
		for (var c : cookiesJSON)
		{
			var cookieJSON = (JSONObject)c;
			var date = cookieJSON.has("expires") ? new Date(cookieJSON.optLong("expires")) : null; 
			var cookie = new Cookie(
					cookieJSON.optString("domain"),
					cookieJSON.getString("name"),
					cookieJSON.optString("value"),
					cookieJSON.optString("path"),
					date,
					cookieJSON.optBoolean("secure"),
					cookieJSON.optBoolean("httpOnly"),
					cookieJSON.optString("sameSite")
				);

			var oldCookie = cookieManager.getCookie(cookie.getName());
			if (oldCookie != null)
			{
				cookieManager.removeCookie(cookie);
			}
			cookieManager.addCookie(cookie);
		}
	}
	
	/**
	 * @see org.jameica.hibiscus.sync.example.ExampleSynchronizeJob#execute()
	 */
	@Override
	public boolean process(Konto konto, boolean fetchSaldo, boolean fetchUmsatz, boolean forceAll, DBIterator<Umsatz> umsaetze, String user, String passwort) throws Exception
	{
		if (konto.getUnterkonto() == null || konto.getUnterkonto().length() != 5)
		{
			var nr = konto.getUnterkonto().replaceAll(" ",  "");
			if (nr.length() == 15)
			{
				konto.setUnterkonto(nr.substring(10));
				konto.store();
			}
		}
		
		if (konto.getUnterkonto() == null || konto.getUnterkonto().length() != 5)
		{
			throw new ApplicationException("Bitte die letzten 5 Ziffern der Kreditkartennummer im Konto bei Unterkontonummer eintragen!");
		}
		
		try 
		{
			ArrayList<Umsatz> neueUmsaetze = new ArrayList<Umsatz>();

			AMEXRequestInterceptor interceptor = null;
			monitor.setPercentComplete(5);
			WebResult response;

			int retry = 0;
			do 
			{
				if (retry > 0)
				{
					log(Level.WARN, "CorrelationId wurde nicht akzeptiert, starte Retry Nr. " + retry + "in " + (retry * 20) + " Sekunden");
					Thread.sleep(retry * 1000 * 20);
				}
				retry++;
				interceptor = GetCorrelationId(konto, user, passwort, retry);

				addDeviceCookies(konto);
				
				response = doRequest(interceptor.Url, HttpMethod.POST, null, "application/x-www-form-urlencoded; charset=UTF-8", interceptor.Body.replace("BlahIdBlah", URLEncoder.encode(user, "UTF-8")).replace("BlahWortBlah", URLEncoder.encode(passwort, "UTF-8")));
			}
			while (response.getHttpStatus() == 403 && retry < 4);
			if (response.getHttpStatus() != 200)
			{
				log(Level.DEBUG, "Response: " + response.getContent());
				throw new ApplicationException("Login fehlgeschlagen, Status = " + response.getHttpStatus());
			}
			var json = response.getJSONObject();
			boolean needLogin = response.getJSONObject().optInt("statusCode") != 0;
			var reAuth = json.optJSONObject("reauth");
			if (reAuth == null)
			{
				interceptor.Url = null;
				log(Level.DEBUG, "Response: " + response.getContent());
				throw new ApplicationException("Login fehlgeschlagen, Passwort falsch?");
			}
			var assessmentToken = reAuth.getString("assessmentToken");
			if (needLogin)
			{
				var applicationId = reAuth.optString("applicationId");
				var actionId = reAuth.optString("actionId");
				var mfaId = reAuth.optString("mfaId");
				monitor.setPercentComplete(6);

				json = new JSONObject(Map.of(
						"assessmentToken", assessmentToken,
						"meta", new JSONObject(Map.of(
								"applicationId", applicationId,
								"authenticationActionId", actionId,
								"locale", "de-DE")),
						"userJourneyIdentifier", "aexp.global:create:session"
						));
				var body = json.toString();
				monitor.setPercentComplete(7);

				response = doRequest("https://functions.americanexpress.com/ReadAuthenticationChallenges.v3", HttpMethod.POST, null, "application/json", body);
				if (response.getHttpStatus() != 200)
				{
					log(Level.DEBUG, "Response: " + response.getContent());
					throw new ApplicationException("Abfrage 2FA-Verfahren fehlgeschlagen, Status = " + response.getHttpStatus());
				}
				json = response.getJSONObject();
				var challengeQuestions = json.getJSONArray("challengeQuestions");
				monitor.setPercentComplete(8);
				JSONObject questionSMS = null;
				JSONObject questionEMAIL = null;
				JSONObject questionAPP = null;
				for (var questionObj : challengeQuestions)
				{
					JSONObject question = (JSONObject) questionObj;
					String otpType = question.optString("category");
					if ("OTP_EMAIL".equals(otpType))
					{
						questionEMAIL = question;
						log(Level.INFO, "OTP-Typ EMAIL gefunden");
					}
					else if ("OTP_SMS".equals(otpType))
					{
						questionSMS = question;
						log(Level.INFO, "OTP-Typ SMS gefunden");
					}
					else if ("OTP_PUSH_NOTIFICATION".equals(otpType))
					{
						questionAPP = question;
						log(Level.INFO, "OTP-Typ PUSH gefunden");
					}
					else 
					{
						questionAPP = question;
						log(Level.INFO, "Unbekannter OTP-Typ " + otpType + " gefunden");
					}
				}

				JSONObject question = null;
				String otpOrder = konto.getMeta(AMEXSynchronizeBackend.META_OTPTYPE, "ESA");
				for (int i = 0; i < otpOrder.length(); i++)
				{
					if (otpOrder.charAt(i) == 'E' && questionEMAIL != null)
					{
						question = questionEMAIL;
						break;
					}
					else if (otpOrder.charAt(i) == 'S' && questionSMS != null)
					{
						question = questionSMS;
						break;
					}
					else if (otpOrder.charAt(i) == 'A' && questionAPP != null)
					{
						question = questionAPP;
						break;
					}
				}

				if (question == null)
				{
					throw new ApplicationException("Kein verwendbares OTP-Verfahren gefunden, bitte Einstellungen \u00FCberpr\u00FCfen, empfohlen wir 'ESA', aktuell '" + otpOrder + "'");
				}

				monitor.setPercentComplete(9);
				var challengeOptions = (JSONObject)question.getJSONArray("challengeOptions").get(0);
				var encryptedValue = challengeOptions.getString("encryptedValue");
				var address = challengeOptions.optString("maskedValue");
				var type = question.getString("category");

				json = new JSONObject(Map.of(
						"locale", "de-DE",
						"otpDeliveryRequest", new JSONObject(Map.of(
								"deliveryMethod", type.replace("OTP_", ""),
								"encryptedValue", encryptedValue
								)),
						"userJourneyIdentifier", "aexp.global:create:session"
						));
				response = doRequest("https://functions.americanexpress.com/CreateOneTimePasscodeDelivery.v3", HttpMethod.POST, null, "application/json", json.toString());
				if (response.getHttpStatus() != 200)
				{
					log(Level.DEBUG, "Response: " + response.getContent());
					throw new ApplicationException("Anforderung 2. Faktor fehlgeschlagen, Status = " + response.getHttpStatus());
				}
				encryptedValue = response.getJSONObject().getString("encryptedChannelValue");
				monitor.setPercentComplete(10);
				boolean pendingChallenges;
				do
				{
					if (address != null && question.equals(questionAPP))
					{
						json = new JSONObject(Map.of(
								"assessmentToken", assessmentToken,
								"userJourneyIdentifier", "aexp.global:create:session"
								));
					}
					else
					{
						var otp = Application.getCallback().askUser("Bitte geben Sie den Verifizierungscode ein" + (address != null ? ", der an " + address + " geschickt wurde": ""), "Verifizierungscode:");
						monitor.setPercentComplete(11);
	
						json = new JSONObject(Map.of(
								"assessmentToken", assessmentToken,
								"challengeAnswers", new JSONArray(new JSONObject[]{
										new JSONObject(Map.of(
												"encryptedValue", encryptedValue,
												"type", "OTP",
												"value", otp
												))
								}),
								"userJourneyIdentifier", "aexp.global:create:session"
								));
					}
					response = doRequest("https://functions.americanexpress.com/UpdateAuthenticationTokenWithChallenge.v3", HttpMethod.POST, null, "application/json", json.toString());
					if (response.getHttpStatus()!= 200)
					{
						log(Level.DEBUG, "Response: " + response.getContent());
						throw new ApplicationException("Validierung 2. Faktor fehlgeschlagen, Status = " + response.getHttpStatus());
					}
					if (response.getJSONObject().optJSONArray("pendingChallenges") != null  && response.getJSONObject().optJSONArray("pendingChallenges").length() > 0)
					{
						pendingChallenges = true;
						Thread.sleep(5000);
					}
					else
					{
						pendingChallenges = false;
					}
				} while (pendingChallenges);
				monitor.setPercentComplete(12);

				var mfaTime = Calendar.getInstance();
				response = doRequest(interceptor.Url, HttpMethod.POST, null, "application/x-www-form-urlencoded; charset=UTF-8", "request_type=login&Face=de_DE&Logon=Logon&version=4&mfaId=" + mfaId + "&b_hour=" + mfaTime.get(Calendar.HOUR_OF_DAY) + "&b_minute=" + mfaTime.get(Calendar.MINUTE) + "&b_second=" + mfaTime.get(Calendar.SECOND) + "&b_dayNumber=" + mfaTime.get(Calendar.DAY_OF_MONTH) + "&b_month=" + mfaTime.get(Calendar.MONTH) + "&b_year=" + mfaTime.get(Calendar.YEAR) + "&b_timeZone=2");
				if (response.getHttpStatus() != 200)
				{
					log(Level.DEBUG, "Response: " + response.getContent());
					throw new ApplicationException("Finaler Login fehlgeschlagen, Status = " + response.getHttpStatus());
				}
				monitor.setPercentComplete(13);

				needLogin = response.getJSONObject().optInt("statusCode") != 0;

				if (!needLogin && "true".equals(konto.getMeta(AMEXSynchronizeBackend.META_TRUST, "true")))
				{
					response = doRequest("https://functions.americanexpress.com/CreateTwoFactorAuthenticationForUser.v1", HttpMethod.POST, null, "application/json", "[{\"locale\":\"de-DE\",\"trust\":true,\"deviceName\":\"SyncusGnampfus\"}]");
					if (response.getHttpStatus() != 200)
					{
						log(Level.DEBUG, "Response für Remember: " + response.getHttpStatus() + " - " + response.getContent());
					}
					else 
					{
						ArrayList<String> cookieNames = new ArrayList<>();
						var cookiesJSON = new JSONArray(); //konto.getMeta(AMEXSynchronizeBackend.META_DEVICECOOKIES, "[]"));
						for (var header : response.getResponseHeader())
						{
							if ("set-cookie".equals(header.getName().toLowerCase()) && header.getValue().toLowerCase().contains("domain=.americanexpress.com"))
							{
								cookieNames.add(header.getValue().replaceAll("=.*", ""));
							}
						}
						var cookieManager = webClient.getCookieManager();
						for (var name : cookieNames)
						{
							var allCookies = cookieManager.getCookies();
							for (var cookie : allCookies)
							{
								if (cookie.getName().equals(name) && cookie.getDomain().equals(".americanexpress.com"))
								{
									var cookieJSON = new JSONObject();
									cookieJSON.put("domain", cookie.getDomain());
									cookieJSON.put("name", cookie.getName());
									cookieJSON.put("value", cookie.getValue());
									cookieJSON.put("path", cookie.getPath());
									if (cookie.getExpires() != null)
									{
										cookieJSON.put("expires", cookie.getExpires().getTime());
									}
									cookieJSON.put("secure", cookie.isSecure());
									cookieJSON.put("httpOnly",  cookie.isHttpOnly());
									cookieJSON.put("sameSite", cookie.getSameSite());
									cookiesJSON.put(cookieJSON);
									break;
								}
							}
						}
						konto.setMeta(AMEXSynchronizeBackend.META_DEVICECOOKIES, cookiesJSON.toString());
					}
				}
			}
			monitor.setPercentComplete(14);

			if (needLogin)
			{
				throw new ApplicationException("Login fehlgeschlagen");
			}
			monitor.setPercentComplete(15);

			var accountToken = konto.getMeta(AMEXSynchronizeBackend.META_ACCOUNTTOKEN, null);
			if (accountToken == null || accountToken.isBlank())
			{
				log(Level.INFO, "Ermittle AccountToken (neue Variante)");
				try 
				{
					response = doRequest("https://global.americanexpress.com/activity/recent?appv5=false", HttpMethod.GET, null, null, null, true);
					if (response.getHttpStatus() != 200)
					{
						log(Level.DEBUG, "Response: " + response.getContent());
						throw new ApplicationException("Abfrage Konten fehlgeschlagen, Status = " + response.getHttpStatus());
					}
					var initialState = ((HtmlPage)response.getPage()).executeJavaScript("window.__INITIAL_STATE__").getJavaScriptResult();
					if (initialState != null)
					{
						var initialStateText = initialState.toString();
						var initialArray = new JSONArray(initialStateText);
						var a = initialArray.getJSONArray(1);
						for (int i = 0; i < a.length(); i++)
						{
							if ("modules".equals(a.optString(i)))
							{
								var b = a.getJSONArray(i+1).getJSONArray(1);
								for (i = 0; i < b.length(); i++)
								{
									if ("axp-myca-root".equals(b.optString(i)))
									{
										var c = b.getJSONArray(i+1).getJSONArray(1);
										for (i = 0; i < c.length(); i++)
										{
											if ("products".equals(c.optString(i)))
											{
												var d = c.getJSONArray(i+1).getJSONArray(1);
												for (i = 0; i < d.length(); i++)
												{
													if ("details".equals(d.optString(i)))
													{
														var e = d.getJSONArray(i+1).getJSONArray(1);
														for (i = 0; i < e.length(); i++)
														{
															if ("types".equals(e.optString(i))) 
															{
																var f = e.getJSONArray(i+1).getJSONArray(1);
																for (i = 0; i < f.length(); i++)
																{
																	if ("CARD_PRODUCT".equals(f.optString(i))) 
																	{
																		var g = f.getJSONArray(i+1);
																		for (i = 0; i < g.length(); i++)
																		{
																			if ("productsList".equals(g.optString(i)))
																			{
																				var h = g.getJSONArray(i+1);
																				for (i = 0; i < h.length(); i++)
																				{
																					var arr = h.optJSONArray(i); 
																					if (arr != null)
																					{
																						var token = h.getString(i - 1);
																						for (var j = 0; j < h.length(); j++)
																						{
																							if ("account".equals(arr.optString(j)))
																							{
																								var accArr = arr.getJSONArray(j+1);
																								for (j = 0; j < accArr.length(); j++) 
																								{
																									if ("display_account_number".equals(accArr.optString(j)) && konto.getUnterkonto().equals(accArr.optString(j + 1)))
																									{
																										konto.setMeta(AMEXSynchronizeBackend.META_ACCOUNTTOKEN, token);
																										accountToken = token;
																										break;
																									}
																								}
																								break;
																							}
																						}
																					}
																				}
																				break;
																			}
																		}
																		break;
																	}
																}
																break;
															}
														}
														break;
													}
												}
												break;
											}
										}
										break;
									}
								}
								
								break;
							}
						}
					}
				}
				catch (Exception e)
				{
					log(Level.ERROR, "Ermittlung AccountToken mit neuer Variante fehlgeschlagen: " + e);
					accountToken = null;
				}
			}

			if (accountToken == null || accountToken.isBlank())
			{
				log(Level.INFO, "Ermittel AccountToken (alte Variante)");
				response.getContent().lines().forEach(line -> 
				{
					if (line.contains("\\\"accountToken\\\"")) 
					{
						try 
						{
							konto.setMeta(AMEXSynchronizeBackend.META_ACCOUNTTOKEN, line.replace("\\","").replaceAll(".*\"accountToken\",\"([^\"]+)\".*", "$1"));
						} catch (RemoteException e) { }
					}
				});
				accountToken = konto.getMeta(AMEXSynchronizeBackend.META_ACCOUNTTOKEN, null);

				if (konto.getMeta(AMEXSynchronizeBackend.META_ACCOUNTTOKEN, null) == null)
				{
					log(Level.DEBUG, "Response: " + response.getContent());
					throw new ApplicationException("Ermittlung Konten fehlgeschlagen");
				}
				else
				{
					log(Level.INFO, "Kontendaten ermittelt");
				}
			}
			monitor.setPercentComplete(20);

			ArrayList<KeyValue<String, String>> header = new ArrayList<>();
			header.add(new KeyValue<>("account_token", accountToken));

			response = doRequest("https://global.americanexpress.com/api/servicing/v1/financials/balances", HttpMethod.GET, header, null, null);
			if (response.getHttpStatus() != 200)
			{
				log(Level.DEBUG, "Response: " + response.getContent());
				throw new ApplicationException("Saldostatusabfrage fehlgeschlagen, Status = " + response.getHttpStatus());
			}
			var saldo = new SaldoContainer(-response.getJSONArray().getJSONObject(0).getDouble("statement_balance_amount"));
			if (fetchSaldo)
			{
				konto.setSaldo(saldo.value); // last_statement_balance_amount, interest_saver_amount
				konto.store();
				Application.getMessagingFactory().sendMessage(new SaldoMessage(konto));
			}
			monitor.setPercentComplete(25);

			if (fetchUmsatz) 
			{
				log(Level.INFO, "Hole Reservierungen");
				response = doRequest("https://global.americanexpress.com/api/servicing/v1/financials/transactions?limit=1000&status=pending&extended_details=merchant", HttpMethod.GET, header, null, null);
				if (response.getHttpStatus() != 200)
				{
					log(Level.DEBUG, "Response: " + response.getContent());
					throw new ApplicationException("Abruf unverbuchter Transaktionen fehlgeschlagen, Status = " + response.getHttpStatus());
				}
				var duplikate = processTransactions(konto, neueUmsaetze, umsaetze, response.getJSONObject().getJSONArray("transactions"), true, null);				
				monitor.setPercentComplete(30);
				
				log(Level.INFO, "L\u00f6sche nicht mehr existierende Reservierungen");
				deleteMissingUnbooked(duplikate);
				monitor.setPercentComplete(35);

				log(Level.INFO, "Hole Buchungsperioden");
				response = doRequest("https://global.americanexpress.com/api/servicing/v1/financials/statement_periods", HttpMethod.GET, header, null, null);
				if (response.getHttpStatus() != 200)
				{
					log(Level.DEBUG, "Request: " + response.getContent());
					throw new ApplicationException("Abruf Buchungsperioden fehlgeschlagen, Status = " + response.getHttpStatus());
				}
				var periods = response.getJSONArray();
				monitor.setPercentComplete(40);
				int step = 50 / periods.length();
				for (var periodObj : periods)
				{
					var period = (JSONObject)periodObj;
					log(Level.INFO, "Abruf Buchungen " +period.getString("statement_start_date") + " bis " + period.getString("statement_end_date"));

					response = doRequest("https://global.americanexpress.com/api/servicing/v1/financials/transactions?limit=1000&extended_details=merchant&statement_end_date=" + period.getString("statement_end_date") + "&status=posted", HttpMethod.GET, header, null, null);
					if (response.getHttpStatus() != 200)
					{
						log(Level.DEBUG, "Request: " + response.getContent());
						throw new ApplicationException("Abruf Buchungen " +period.getString("statement_start_date") + " bis " + period.getString("statement_end_date") + " fehlgeschlagen, Status = " + response.getHttpStatus());
					}
					if (!forceAll && !processTransactions(konto, neueUmsaetze, umsaetze, response.getJSONObject().getJSONArray("transactions"), false, saldo).isEmpty())
					{
						break;
					}
					monitor.setPercentComplete(monitor.getPercentComplete() + step);
				}

				monitor.setPercentComplete(95);
				reverseImport(neueUmsaetze);
			}
		}
		finally
		{
			doRequest("https://functions.americanexpress.com/DeleteUserSession.v1", HttpMethod.GET, null, null, null);
		}
		return true;
	}

	private class SaldoContainer 
	{
		public Double value;
		public SaldoContainer(Double saldo) {value = saldo; }
	}

	private ArrayList<Umsatz> processTransactions(Konto konto, ArrayList<Umsatz> neueUmsaetze, DBIterator<Umsatz> vorhandeneUmsaetze, JSONArray transactions, boolean pending, SaldoContainer saldo) throws RemoteException, ParseException, ApplicationException
	{
		var accountToken = konto.getMeta(AMEXSynchronizeBackend.META_ACCOUNTTOKEN, "");
		var kontoNr = konto.getUnterkonto();
		var duplikate = new ArrayList<Umsatz>();
		
		for (var transObj : transactions)
		{
			var transaction = (JSONObject)transObj;
			if (accountToken.equals(transaction.optString("account_token")) && kontoNr.equals(transaction.optString("display_account_number")))
			{
				var newUmsatz = (Umsatz) Settings.getDBService().createObject(Umsatz.class,null);
				newUmsatz.setKonto(konto);
				if (pending)
				{
					newUmsatz.setFlags(Umsatz.FLAG_NOTBOOKED);
				}
				newUmsatz.setTransactionId(transaction.optString("identifier"));
				newUmsatz.setZweck(transaction.optString("description").replaceAll(" +", " "));
				newUmsatz.setDatum(dateFormat.parse(transaction.getString("charge_date")));
				newUmsatz.setValuta(dateFormat.parse(transaction.getString("charge_date")));
				newUmsatz.setBetrag(-transaction.optDouble("amount"));
				newUmsatz.setCustomerRef(transaction.optString("reference_id"));
				if (saldo != null)
				{
					newUmsatz.setSaldo(saldo.value);
					saldo.value -= newUmsatz.getBetrag();
				}
				var extended = transaction.optJSONObject("extended_details");
				if (extended != null)
				{
					var merchant = extended.optJSONArray("merchant");
					if (merchant != null)
					{
						newUmsatz.setGegenkontoName(extended.optString("display_name"));
					}
				}
	
				Umsatz vorhandenerUmsatz = getDuplicateById(newUmsatz);
				if (vorhandenerUmsatz != null) 
				{
					if (!pending && vorhandenerUmsatz.hasFlag(Umsatz.FLAG_NOTBOOKED))
					{
						vorhandenerUmsatz.setFlags(Umsatz.FLAG_NONE);
						vorhandenerUmsatz.store();
						Application.getMessagingFactory().sendMessage(new ObjectChangedMessage(vorhandenerUmsatz));
					}
					if (vorhandenerUmsatz.getTransactionId() == null)
					{
						vorhandenerUmsatz.setTransactionId(newUmsatz.getTransactionId());
						vorhandenerUmsatz.store();
						Application.getMessagingFactory().sendMessage(new ObjectChangedMessage(vorhandenerUmsatz));
					}
					duplikate.add(vorhandenerUmsatz);
				}
				else
				{
					neueUmsaetze.add(newUmsatz);
				}
			}
		}
		return duplikate;
	}
}
