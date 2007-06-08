/*
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2, or (at your option)
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA
 * 02111-1307, USA.
 *
 * http://www.gnu.org/copyleft/gpl.html
 */
package net.sf.l2j.gameserver.model.actor.instance;

import java.util.concurrent.Future;
import javolution.util.FastMap;

import net.sf.l2j.gameserver.ThreadPoolManager;
import net.sf.l2j.gameserver.ai.CtrlIntention;
import net.sf.l2j.gameserver.datatables.SkillTable;
import net.sf.l2j.gameserver.model.L2Character;
import net.sf.l2j.gameserver.model.L2ItemInstance;
import net.sf.l2j.gameserver.model.L2Skill;
import net.sf.l2j.gameserver.templates.L2NpcTemplate;
import net.sf.l2j.util.Rnd;

/**
 * 
 * This class ...
 * 
 * @version $Revision: 1.15.2.10.2.16 $ $Date: 2005/04/06 16:13:40 $
 */
public final class L2BabyPetInstance extends L2PetInstance
{
	protected int _weakHealId;
	protected int _strongHealId;
    private Future _healingTask;
	
	public L2BabyPetInstance(int objectId, L2NpcTemplate template, L2PcInstance owner, L2ItemInstance control)
	{
		super(objectId, template, owner, control);
		
		// look through the skills that this template has and find the weak and strong heal.
		FastMap<Integer, L2Skill> skills = (FastMap<Integer, L2Skill>) getTemplate().getSkills();
		L2Skill skill1 = null;
		L2Skill skill2 = null;
		
		for (L2Skill skill: skills.values())
		{
			// just in case, also allow cp heal and mp recharges to be considered here...you never know ;)
			if ( skill.isActive() && (skill.getTargetType() == L2Skill.SkillTargetType.TARGET_OWNER_PET) && 
					((skill.getSkillType() == L2Skill.SkillType.HEAL) || 
					(skill.getSkillType() == L2Skill.SkillType.HOT) ||
					(skill.getSkillType() == L2Skill.SkillType.BALANCE_LIFE) ||
					(skill.getSkillType() == L2Skill.SkillType.HEAL_PERCENT) ||
					(skill.getSkillType() == L2Skill.SkillType.HEAL_STATIC) ||
					(skill.getSkillType() == L2Skill.SkillType.COMBATPOINTHEAL) ||
					(skill.getSkillType() == L2Skill.SkillType.CPHOT) ||
					(skill.getSkillType() == L2Skill.SkillType.MANAHEAL) ||
					(skill.getSkillType() == L2Skill.SkillType.MANA_BY_LEVEL) ||
					(skill.getSkillType() == L2Skill.SkillType.MANAHEAL_PERCENT) ||
					(skill.getSkillType() == L2Skill.SkillType.MANARECHARGE) ||
					(skill.getSkillType() == L2Skill.SkillType.MPHOT) )
				)
			{
				// only consider two skills.  If the pet has more, too bad...they won't be used by its AI.
				// for now assign the first two skills in the order they come.  Once we have both skills, re-arrange them  
				if (skill1 == null)
					skill1 = skill;
				else
				{
					skill2 = skill;
					break;
				}
			}
		}
		// process the results.  Only store the ID of the skills.  The levels are generated on the fly, based on the pet's level!
		if (skill1 == null)
		{
			_weakHealId = 0;
			_strongHealId = 0;
		}
		else
		{
			if (skill2 == null)
			{
				 // duplicate so that the same skill will be used in both normal and emergency situations
				_weakHealId = skill1.getId();
				_strongHealId = _weakHealId;
			}
			else
			{
				// arrange the weak and strong skills appropriately
				if(skill1.getPower() > skill2.getPower())
				{
					_weakHealId = skill2.getId();
					_strongHealId = skill1.getId();
				}
				else
				{
					_weakHealId = skill1.getId();
					_strongHealId = skill2.getId();
				}
			}

			// start the healing task
			_healingTask = ThreadPoolManager.getInstance().scheduleEffectAtFixedRate(new Heal(this), 0, 1000);
		}
	}
	
	public synchronized void doDie(L2Character killer) {
		super.doDie(killer);
		
		if (_healingTask != null)
		{
			_healingTask.cancel(false);
			_healingTask = null;
		}
	}
	
    public void doRevive()
    {
    	super.doRevive();
		if (_healingTask == null)
			_healingTask = ThreadPoolManager.getInstance().scheduleEffectAtFixedRate(new Heal(this), 0, 1000);
    }
	
    private class Heal implements Runnable
    {
    	private L2BabyPetInstance _baby;
    	public Heal(L2BabyPetInstance baby)
    	{
    		_baby = baby;
    	}
    	
        public void run()
        {
        	L2PcInstance owner = _baby.getOwner();
        	
        	// if the owner is dead, merely wait for the owner to be resurrected
            if (!owner.isDead())
            {
            	// find which skill (if any) to cast and at what level 
            	int babyLevel = _baby.getLevel();
            	int skillLevel = babyLevel / 10;
            	if (skillLevel < 1)
            		skillLevel = 1;
            	
            	if(babyLevel >= 70)
            		skillLevel += (babyLevel-65)/10;

            	L2Skill skillToCast = null;

            	// if the owner's HP is more than 80%, do nothing.  
            	// if the owner's HP is very low (less than 20%) have a high chance for strong heal
            	// otherwise, have a low chance for weak heal
            	if ((owner.getCurrentHp()/owner.getMaxHp() < 0.2) && Rnd.get(4) < 3)
            		skillToCast = SkillTable.getInstance().getInfo(_strongHealId, skillLevel);
            	else if ((owner.getCurrentHp()/owner.getMaxHp() < 0.8) && Rnd.get(4) < 1)
            		skillToCast = SkillTable.getInstance().getInfo(_weakHealId, skillLevel); 
            	
            	if (skillToCast != null)
            	{
	        		// after the pet is done casting, it should return to whatever it was doing 
	        		CtrlIntention oldIntention = getAI().getIntention();
	        		_baby.doCast(skillToCast );
	        		getAI().setIntention(oldIntention, owner);
            	}
            }
        }
    }
}
