package com.identity4j.connector.mysql.users;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.identity4j.connector.ConnectorCapability;
import com.identity4j.connector.exception.ConnectorException;
import com.identity4j.connector.exception.PrincipalNotFoundException;
import com.identity4j.connector.jdbc.JDBCConnector;
import com.identity4j.connector.jdbc.JDBCIdentity;
import com.identity4j.connector.principal.Identity;
import com.identity4j.util.CollectionUtil;
import com.identity4j.util.StringUtil;

/**
 * MySQL User Connector helps managing users in MySQL user table.
 * <br />
 * It stores the grants associated with a user in attributes map by key USER_ACCESS. These grants are stored without the keyword GRANT.
 * <br />
 * e.g.
 * <code>
 * 	USAGE ON *.* TO 'leo'@'mylaptop'
 *  <br />
 *  ALL PRIVILEGES ON *.* TO 'root'@'localhost'
 *  <br />
 *  <b>Note:</b> Passwords are not stored along with these grants.
 * </code>
 * 
 * @author gaurav
 *
 */
public class MySQLUsersConnector extends JDBCConnector{
	
	private static Set<ConnectorCapability> capabilities = new HashSet<ConnectorCapability>(Arrays.asList(new ConnectorCapability[] { 
			ConnectorCapability.passwordChange,
			ConnectorCapability.passwordSet,
			ConnectorCapability.createUser,
			ConnectorCapability.deleteUser,
			ConnectorCapability.updateUser,
			ConnectorCapability.authentication,
			ConnectorCapability.identities
	}));
	
	/**
	 * Creates user in MySQL database along with access(Grants) given.
	 * <br/>
	 * User is created in two steps.
	 * <br/>
	 * <ol>
	 * 	<li>User is created using MySQL <b>Create</b> command.</li>
	 * 	<li>User is granted access using MySQL <b>Grant</b> command.
	 * 		All the access to be given to an identity is set in Attributes map by key <b>USER_ACCESS</b>
	 * </li>
	 * </ol>
	 * <br />
	 * The operation happens in a JDBC Transaction.
	 * <br />
	 * The new grants to be passed should be in format as displayed in MySQL 'SHOW GRANT' command.
	 * <br />
	 * GRANT USAGE ON *.* TO 'myuser'@'localhost' IDENTIFIED BY PASSWORD '*...'
	 * <br />
     * GRANT ALL PRIVILEGES ON `myapp`.* TO 'myuser'@'localhost'
	 * <br />
	 * The database name should be enclosed with <b>`</b>.
	 * 
	 */
	@Override
	public Identity createIdentity(final Identity identity, final char[] password)
			throws ConnectorException {
		
		inTransaction(new JDBCBlock() {
			
			@Override
			public void apply(Statement statement) throws SQLException {
				UserHost userHost = UserHost.get(identity.getPrincipalName());
				
				//create user
				statement.addBatch(getMySQLUserConfiguration().getCreateIdentitySQL(userHost.user, userHost.host, new String(password)));
				
				//grant all access
				List<String> accesses = StringUtil.toList(identity.getAttribute(MySqlUsersConstants.USER_ACCESS),MySqlUsersConstants.NEW_LINE);
				for (String access : accesses) {
					statement.addBatch(getMySQLUserConfiguration().getGrantIdentitySQL(access, userHost.user, userHost.host));
				}
				
			}
		});
		
		return identity;
	}
	
	/**
	 * Fetches all MySQL Identities along with all the database grants associated with it.
	 */
	@Override
	public Iterator<Identity> allIdentities() throws ConnectorException {
		final List<Identity> identities = new ArrayList<Identity>();
		
		jdbcAction(getMySQLUserConfiguration()
					.getSelectIdentitiesSQL(), new String[0], new JDBCResultsetBlock<Void>() {

						@Override
						public Void apply(ResultSet resultSet)
								throws SQLException {
							Identity identity = null;
							while (resultSet.next()) {
								identity = prepareIdentity(resultSet);
								identities.add(identity);
							}
							return null;
						}
					});
			
		return identities.iterator();
	}
	
	/**
	 * Fetches a MySQL Identity along with all the database grants associated with it.
	 * <br />
	 * <b>Note : </b> Disabled My SQL user will not fetch all the grants associated with original
	 * user.
	 */
	@Override
	public Identity getIdentityByName(final String name)
			throws PrincipalNotFoundException, ConnectorException {
		
		final UserHost userHost = UserHost.get(name);
		
		return jdbcAction(getMySQLUserConfiguration()
					.getSelectIdentitySQL(), new Object[]{
			//check for the user actually the way mysql stores
			name,
			//check for the user in case disable flag is appended to it
			getMySQLUserConfiguration().getDisabledIdentityPrincipalName(userHost.user, userHost.host)},
			new JDBCResultsetBlock<Identity>() {

				@Override
				public Identity apply(ResultSet resultSet) throws SQLException {
					JDBCIdentity identity = null;
					
					int flag = -1;
					while (resultSet.next()) {
						identity = prepareIdentity(resultSet);
						
						//increment the flag to check how many records processed
						//the query contains OR condition, hence an extra precautionary check
						++flag;
					}
					
					//if flag count does not changes, no principal found
					if(flag == -1) throw new PrincipalNotFoundException(name + " not found.");
					
					//if flag count is more than 0, i.e. we have more than one record
					if(flag > 0) throw new ConnectorException("Found more than one record for user " + userHost);
					
					return identity;
				}
			});
		
	}

	/**
	 * Helper method which sets the properties of identity from JDBC result set.
	 * 
	 * @param resultSet
	 * @return
	 * @throws SQLException
	 */
	private JDBCIdentity prepareIdentity(ResultSet resultSet)
			throws SQLException {
		JDBCIdentity identity;
		//checking for host, if disable flag is appended we need to extract it from host
		//and also set disable flag in account status
		String host = resultSet.getString(MySqlUsersConstants.USER_TABLE_HOST_COLUMN);
		if(host.startsWith(getMySQLUserConfiguration().getDisableFlag())){
			host = StringUtil.getAfter(host, getMySQLUserConfiguration().getDisableFlag());
		}
		String principalName = getMySQLUserConfiguration()
				.getEnabledIdentityPrincipalName(
						resultSet
								.getString(MySqlUsersConstants.USER_TABLE_USER_COLUMN),
						host);
		identity = new JDBCIdentity(principalName,principalName);
		//check identity is enabled or disabled
		identity.getAccountStatus().setDisabled(resultSet.getString(MySqlUsersConstants.USER_TABLE_HOST_COLUMN).
				startsWith(getMySQLUserConfiguration().getDisableFlag()));
		
		//fetch all grants
		identity.setAttribute(MySqlUsersConstants.USER_ACCESS, fetchGrants(resultSet
				.getString(MySqlUsersConstants.USER_TABLE_USER_COLUMN),resultSet.getString(MySqlUsersConstants.USER_TABLE_HOST_COLUMN)));
		
		return identity;
	}
	
	/**
	 * First we fetch the identity from database to check if it was disabled by prepending
	 * flag to host column, if identity was disabled we will have to prepend flag to host and
	 * then fire SQL for drop user.
	 * <br />
	 * <b>Note: </b> If identity is disabled we have to drop the user for both the hosts i.e with enabled
	 * and disabled.
	 */
	@Override
	public void deleteIdentity(String principalName) throws ConnectorException {
		Identity identity = getIdentityByName(principalName);

		if(identity.getAccountStatus().isDisabled()){
			UserHost userHost = UserHost.get(identity,getMySQLUserConfiguration().getDisableFlag());
			updateHelper(getMySQLUserConfiguration().getDeleteIdentitySQL(), userHost.user,userHost.host);
		}
		
		UserHost userHost = UserHost.get(identity.getPrincipalName());
		updateHelper(getMySQLUserConfiguration().getDeleteIdentitySQL(), userHost.user,userHost.host);
	}
	
	/**
	 * First we fetch the identity from database to check if it was disabled by prepending
	 * flag to host column, if identity was disabled we will have to prepend flag to host and
	 * then fire SQL for password.
	 */
	@Override
	protected boolean areCredentialsValid(Identity identity, char[] password)
			throws ConnectorException {
		
		// Encode the password, if its 'plain' then the database will encode it
		// most likely using PASSWORD() function or similar.
		String encodedPassword = new String(encoderManager.encode(password,
				configuration.getIdentityPasswordEncoding(),
				configuration.getCharset(), null, null));
		
		UserHost userHost = UserHost.get(identity,getMySQLUserConfiguration().getDisableFlag());
		
		return jdbcAction(getMySQLUserConfiguration()
					.getSelectPasswordSQL(), new Object[]{encodedPassword,userHost.user,userHost.host}, new JDBCResultsetBlock<Boolean>() {

						@Override
						public Boolean apply(ResultSet resultSet)
								throws SQLException {
							return resultSet.next();
						}
					});
	}
	
	/**
	 * Sets a new password for a User.
	 */
	@Override
	protected void setPassword(Identity identity, char[] password,
			boolean forcePasswordChangeAtLogon, PasswordResetType type) throws ConnectorException {
		UserHost userHost = UserHost.get(identity,getMySQLUserConfiguration().getDisableFlag());
		updateHelper(getMySQLUserConfiguration().getPasswordSetSQL(),userHost.user,userHost.host,new String(password));
	}
	
	/**
	 * Disables a MySQL User by prepending disable flag to Host column.
	 * <br />
	 * If enable/disable feature is not enabled, method call will throw ConnectorException
	 * <br />
	 * <b>Note : </b> Disabled My SQL user will not fetch all the grants associated with original
	 * user.
	 */
	@Override
	public void disableIdentity(Identity identity) {
		if(!getMySQLUserConfiguration().getIdentityEnableDisableFeature()){
			throw new ConnectorException("Feature to enable/disable a user is not enabled, cannot perform the operation.");
		}
		UserHost userHost = UserHost.get(identity,getMySQLUserConfiguration().getDisableFlag());
		enableDisableHelper(userHost,getMySQLUserConfiguration().getDisableFlag() + userHost.host);
		identity.getAccountStatus().setDisabled(true);
	}
	
	/**
	 * Enables a MySQL User by removing prepended disable flag from Host column.
	 * <br />
	 * If enable/disable feature is not enabled, method call will throw ConnectorException
	 * <br />
	 * <b>Note : </b> Disabled My SQL user will not fetch all the grants associated with original
	 * user.
	 */
	@Override
	public void enableIdentity(Identity identity) {
		if(!getMySQLUserConfiguration().getIdentityEnableDisableFeature()){
			throw new ConnectorException("Feature to enable/disable a user is not enabled, cannot perform the operation.");
		}
		UserHost userHost = UserHost.get(identity,getMySQLUserConfiguration().getDisableFlag());
		enableDisableHelper(userHost,UserHost.get(identity.getPrincipalName()).host);
		identity.getAccountStatus().setDisabled(false);
	}
	
	
	/**
	 * Fetches all GRANTS for a user.
	 * 
	 * @param user
	 * @param host
	 * @return all grants associated with 'user'@'host'
	 */
	private String fetchGrants(String user,String host){
		
		return jdbcAction(getMySQLUserConfiguration()
					.getGrantShowIdentitySQL(), new Object[]{user,host}, new JDBCResultsetBlock<String>() {

						@Override
						public String apply(ResultSet resultSet)
								throws SQLException {
							List<String> grants = new ArrayList<String>();
							while(resultSet.next()){
								grants.add(resultSet.getString(1));
							}
							
							return parseGrants(grants);
						}
					});
	}
	
	/**
	 * Fetches all grants associated with an identity
	 * 
	 * @param identity
	 * @return
	 */
	private List<String> findAllGrants(Identity identity) {
		UserHost userHost = UserHost.get(identity, getMySQLUserConfiguration().getDisableFlag());
		String grants = fetchGrants(userHost.user, userHost.host);
		return StringUtil.toList(grants,MySqlUsersConstants.NEW_LINE);
	}
	
	protected MySQLUsersConfiguration getMySQLUserConfiguration(){
		return (MySQLUsersConfiguration) configuration;
	}
	
	@Override
	public Set<ConnectorCapability> getCapabilities() {
		return capabilities;
	}
	
	/**
	 * Update handles granting of new grants or revoking grants.
	 * <br />
	 * The new grants to be passed should be in format as displayed in MySQL 'SHOW GRANT' command.
	 * <br />
	 * GRANT USAGE ON *.* TO 'myuser'@'localhost' IDENTIFIED BY PASSWORD '*...'
	 * <br />
     * GRANT ALL PRIVILEGES ON `myapp`.* TO 'myuser'@'localhost'
	 * <br />
	 * The database name should be enclosed with <b>`</b>.String comparison is done while calculating which grants are new and which
	 * have to be revoked.
	 */
	@Override
	public void updateIdentity(Identity identity) throws ConnectorException {
		adjustAdditionRemovalOfGrantsOnIdentityUpdate(identity);
	}
	
	/**
	 * Method parses all the grants and removes keywords 'GRANT' and 'IDENTIFIED BY PASSWORD'.
	 * <p>
	 * 	 GRANT ALL PRIVILEGES ON *.* TO 'root'@'localhost' IDENTIFIED BY PASSWORD '*..' WITH GRANT OPTION
	 * 	 <br/>
	 *   <b>TO</b>
	 *   <br />
	 *   ALL PRIVILEGES ON *.* TO 'root'@'localhost' WITH GRANT OPTION	
	 * </p>
	 * @param grants
	 * @return
	 */
	private String parseGrants(List<String> grants){
		StringBuilder parsedGrants = new StringBuilder();
		for (String grant : grants) {
			grant = grant.replaceAll(MySqlUsersConstants.GRANT_MATCHER, MySqlUsersConstants.EMPTY_STRING);
			//if grant contains password, remove it
			if(grant.contains(MySqlUsersConstants.IDENTIFIED_BY_PASSWORD)){
				grant = grant.replaceAll(MySqlUsersConstants.PASSWORD_MATCHER, MySqlUsersConstants.EMPTY_STRING);
			}
			parsedGrants.append(StringUtil.getBefore(grant,MySqlUsersConstants._TO_)).append(MySqlUsersConstants.NEW_LINE);
		}
		return parsedGrants.toString();
	}
	
	/**
	 * Helper which toggles(prepends/removes the disable flag) the host column in MYSQL USER table.
	 *  
	 * @param userHost
	 * @param newHostName
	 */
	private void enableDisableHelper(UserHost userHost,String newHostName) {
		PreparedStatement statementEnableDisable = null;
		Statement flush = null;
		try {
			connect.setAutoCommit(false);
			statementEnableDisable = connect.prepareStatement(getMySQLUserConfiguration().getEnableDisableIdentitySQL());
			
			statementEnableDisable.setString(1, newHostName);
			statementEnableDisable.setString(2, userHost.user);
			statementEnableDisable.setString(3, userHost.host);
			
			statementEnableDisable.executeUpdate();
			
			//We need to flush privileges, else latest updates made to mysql.user tables are not reflected
			//in mysql space
			flush = connect.createStatement();
			flush.execute(getMySQLUserConfiguration().getFlushPrivilegesSQL());
			
			connect.commit();
			
		} catch (SQLException e) {
			rollback(connect);
			throw new ConnectorException(e);
		} finally {
			autoCommitTrue(connect);
			closeStatement(statementEnableDisable);
			closeStatement(flush);
		}
	}
	
	/**
	 * Helper utility method to adjust addition and removal of roles from an identity.
	 * It compares the roles currently assigned and new set of roles sent and finds which are to be added and which are to 
	 * be removed and accordingly performs removal or addition action.
	 * 
	 * @param identity
	 * 
	 * @throws ConnectorException for api, connection related errors.
	 */
	private void adjustAdditionRemovalOfGrantsOnIdentityUpdate(Identity identity){
		try{
			Set<String> grantsCurrentlyAssigned = new HashSet<String>(findAllGrants(identity));
			Set<String> grantsToBeAssigned = new HashSet<String>(StringUtil.toList(identity.getAttribute(
					MySqlUsersConstants.USER_ACCESS),
					MySqlUsersConstants.NEW_LINE));
			
			Collection<String> newGrantsToAdd = CollectionUtil.objectsNotPresentInProbeCollection(grantsToBeAssigned, grantsCurrentlyAssigned);
			Collection<String> grantsToRemove = CollectionUtil.objectsNotPresentInProbeCollection(grantsCurrentlyAssigned,grantsToBeAssigned);
			
			updateGrantsForIdentity(identity, newGrantsToAdd, grantsToRemove);
		}catch(Exception e){
			throw new ConnectorException(e.getMessage(), e);
		}
	}
	
	/**
	 * Updates grant for an identity by adding new grants and/or revoking grants
	 * 
	 * @param identity to be updated
	 * @param newGrantsToAdd list of new grants to be associated with identity
	 * @param grantsToRemove list of grants to be revoked from identity
	 */
	private void updateGrantsForIdentity(Identity identity,final Collection<String> newGrantsToAdd,final Collection<String> grantsToRemove){
		final UserHost userHost = UserHost.get(identity, getMySQLUserConfiguration().getDisableFlag());
		
		inTransaction(new JDBCBlock() {
			
			@Override
			public void apply(Statement statement) throws SQLException {
				//grant all access
				for (String grant : newGrantsToAdd) {
					statement.addBatch(getMySQLUserConfiguration().getGrantIdentitySQL(grant, userHost.user, userHost.host));
				}
				
				//revoke all access
				for (String revoke : grantsToRemove) {
					statement.addBatch(getMySQLUserConfiguration().getRevokeIdentitySQL(revoke, userHost.user, userHost.host));
				}
				
			}
		});
	}


	/**
	 * Class to compute and store host and user from identity principal name
	 * 
	 * @author gaurav
	 *
	 */
	private static class UserHost {
		public String user;
		public String host;
		
		public UserHost(String user,String host){
			this.user = user;
			this.host = host;
		}
		
		/**
		 * Compute user and host, and return instance of UserHost.
		 * If identity is disabled it prepends the flag to host.
		 * 
		 * @param name
		 * @return
		 */
		public static UserHost get(Identity identity,String flag){
			String name = identity.getPrincipalName();
			String[] userAndHost = parseIdentity(name);
			
			String host = userAndHost[1];
			
			//if identity is disabled we need to prepend flag
			if(identity.getAccountStatus().isDisabled()){
				host = flag + host;
			}	
			
			return new UserHost(userAndHost[0], host);
		}
		
		/**
		 * Compute user and host, and return instance of UserHost
		 * 
		 * @param name
		 * @return
		 */
		public static UserHost get(String name){
			String[] userAndHost = parseIdentity(name);
			return new UserHost(userAndHost[0], userAndHost[1]);
		}
		
		/**
		 * Principal name is of form <user>@<host> we split on @ to get user and host.
		 * 
		 * @param name
		 * @return
		 */
		private static String[] parseIdentity(String name) {
			String[] userAndHost = name.split("@");
			
			if(userAndHost.length != 2){
				throw new IllegalArgumentException("User and Host could not be resolved from principal name " + name);
			}
			return userAndHost;
		}

		@Override
		public String toString() {
			return "UserHost [user=" + user + ", host=" + host + "]";
		}
		
		
	}
}
