/*
 *  This file is part of auditshmaudit.
 *
 *  auditshmaudit is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  auditshmaudit is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with auditshmaudit.  If not, see <http://www.gnu.org/licenses/>.
 */

package horse.wtf.auditshmaudit.checks.dns;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import horse.wtf.auditshmaudit.Issue;
import horse.wtf.auditshmaudit.checks.Check;
import horse.wtf.auditshmaudit.checks.Severity;
import horse.wtf.auditshmaudit.configuration.Configuration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.xbill.DNS.*;

import java.util.Arrays;
import java.util.List;

public class DNSCheck extends Check {

    private static final Logger LOG = LogManager.getLogger(DNSCheck.class);

    private static final String C_DNS_SERVER = "dns_server";
    private static final String C_DNS_QUESTION = "dns_question";
    private static final String C_DNS_QUESTION_TYPE = "dns_question_type";
    private static final String C_EXPECTED_ANSWER = "expected_answer";

    public static final String TYPE = "dns";

    private final Configuration configuration;

    public DNSCheck(String id, Configuration configuration) {
        super(id, configuration);

        this.configuration = configuration;
    }

    @Override
    protected List<Issue> check() {
        try {
            String dnsQuestion = configuration.getString(this, C_DNS_QUESTION);
            List<String> expected = configuration.getListOfStrings(this, C_EXPECTED_ANSWER);

            Lookup lookup = new Lookup(
                    dnsQuestion,
                    Type.value(configuration.getString(this, C_DNS_QUESTION_TYPE).toUpperCase())
            );
            lookup.setResolver(new SimpleResolver(configuration.getString(this, C_DNS_SERVER)));

            Record[] lookupResult = lookup.run();

            if (!lookup.getErrorString().equals("successful")) { // meeehhhhh stone age libraries
                if(lookup.getErrorString().equals("host not found")) {
                    addIssue(new Issue(this, "Domain [{}] not found at all.", dnsQuestion));
                    return issues();
                }

                if(lookup.getErrorString().equals("type not found") && (expected == null || expected.isEmpty())) {
                    LOG.debug("Empty result for lookup [{}] and empty result expected. Not raising an issue.", getFullCheckIdentifier());
                    return issues();
                }

                if(lookup.getErrorString().equals("type not found") && (expected != null && !expected.isEmpty())) {
                    addIssue(new Issue(this, "Expected records but did not find any."));
                    return issues();
                }

                throw new RuntimeException("DNS lookup failed with error: " + lookup.getErrorString());
            }

            ImmutableList.Builder<String> recordsBuilder = new ImmutableList.Builder<>();
            for (Record record : lookupResult) {
                switch(record.getType()) {
                    case Type.MX:
                        MXRecord mx = (MXRecord) record;
                        recordsBuilder.add(mx.getTarget().toString(true));
                        break;
                    case Type.A:
                        ARecord a = (ARecord) record;
                        recordsBuilder.add(a.getAddress().getHostAddress());
                        break;
                    case Type.AAAA:
                        AAAARecord aaaa = (AAAARecord) record;
                        recordsBuilder.add(aaaa.getAddress().getHostAddress());
                        break;
                    case Type.CNAME:
                        CNAMERecord cname = (CNAMERecord) record;
                        recordsBuilder.add(cname.getTarget().toString(true));
                        break;
                    case Type.TXT:
                        TXTRecord txt = (TXTRecord) record;
                        recordsBuilder.add(stripTxtRecord(txt.rdataToString()));
                        break;
                    default:
                        throw new RuntimeException("Unsupported DNS question type [" + record.getType() + "].");
                }
            }

            // Make it a set to get rid of possible duplicates.
            ImmutableSet<String> records = ImmutableSet.copyOf(recordsBuilder.build());

            // Check if there is records at all and raise an issue if there are some but we expected none.
            if((expected == null || expected.isEmpty()) && !records.isEmpty()) {
                addIssue(new Issue(this, "Expected no DNS records but found <{}>. The records are: [{}]",
                        records.size(), Joiner.on(", ").join(records)));
                return issues();
            }

            // Check if number of records is correct.
            if (records.size() != expected.size()) {
                addIssue(new Issue(this, "Expected <{}> DNS records but found <{}>. The records are: [{}], but I expected [{}].",
                        expected.size(), records.size(), Joiner.on(", ").join(records), Joiner.on(", ").join(expected)));
                return issues();
            }

            // Check that each expected record was found.
            for (String expectedRecord : expected) {
                if (!records.contains(expectedRecord)) {
                    addIssue(new Issue(this, "Expected records [{}] but found [{}].",
                            Joiner.on(", ").join(expected), Joiner.on(", ").join(records)));
                    return issues();
                }
            }

            return issues();
        } catch(Exception e) {
            throw new RuntimeException("Could not run DNS lookup.", e);
        }
    }

    private String stripTxtRecord(String txt) {
        if (txt.startsWith("\"") && txt.endsWith("\"")) {
            return txt.subSequence(1, txt.length()-1).toString();
        }

        return txt;
    }

    @Override
    public String getCheckType() {
        return TYPE;
    }

    @Override
    public boolean configurationComplete() {
        return configuration.isCheckConfigurationComplete(this, Arrays.asList(
                C_DNS_SERVER,
                C_DNS_QUESTION,
                C_DNS_QUESTION_TYPE
        ));
    }
}
